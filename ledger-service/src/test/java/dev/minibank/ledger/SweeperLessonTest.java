package dev.minibank.ledger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TWO WAYS THE CROSS-REGION SAGA COULD STILL LOSE MONEY, and the machinery
 * that closes them. Both were found by adversarial review, and both share the
 * shape every saga bug in this codebase has had: a failure with nowhere to
 * land, invisible to every invariant.
 *
 * HOLE 1 · the payments consumers ran with enable.auto.commit=true and a
 * catch that swallowed. A transient failure (a DB blip, an exhausted pool)
 * meant the offset advanced anyway and the event was PERMANENTLY lost,
 * silently. For the applier that strands a transfer's arrival forever: the
 * money departed, sits in IN_TRANSIT, and nothing will ever move it again.
 * The broker-side consumers (Settlement, SettlementConsumer) already learned
 * this lesson · own the offset, retry bounded, park what will not go. The
 * payments consumers must follow the same doctrine.
 *
 * HOLE 2 · the saga compensates exactly one failure (destination rejects ->
 * bounced) and nothing else. An arrival event lost in transit leaves money in
 * the IN_TRANSIT clearing account with no owner, no alarm and no repair. The
 * Sweeper is the reconciler: find departures past a threshold with no arrival
 * and no bounce, and RE-PUBLISH the departed event so the at-least-once
 * machinery redelivers it. The applier's idempotent claim absorbs duplicates,
 * so re-publishing a saga that actually completed in a race is harmless ·
 * that is the whole reason this design is safe.
 *
 *   lesson 1  a poisoned event parks · the queue keeps moving
 *   lesson 2  the notifications consumer parks too, in its own database
 *   lesson 3  a stranded departure is found, republished, and lands ONCE
 *   lesson 4  completed and bounced sagas are not the sweeper's business
 *   lesson 5  a relay backlog is not the sweeper's business either
 *   lesson 6  the sweeper actually runs · wired in Main with the other loops
 *
 * Requires: docker compose up -d   (shards :5434/:5435, postgres :5433)
 */
class SweeperLessonTest {

    static final long IGOR = 10, COCO = 11;
    static final int EU = 0, UK = 1;

    @BeforeAll
    static void boot() throws Exception {
        Shards.boot(
                System.getenv().getOrDefault("MINIBANK_SHARD0_URL", "jdbc:postgresql://localhost:5434/minibank"),
                System.getenv().getOrDefault("MINIBANK_SHARD1_URL", "jdbc:postgresql://localhost:5435/minibank"),
                "minibank", "minibank", 5);
        Shards.setResolver(null);   // arithmetic routing: igor -> shard0, coco -> shard1
        for (Shard s : Shards.all()) s.createSchema();
        Notifications.createOwnDatabase();
    }

    @BeforeEach
    void freshWorld() throws Exception {
        Fixtures.resetShards();
        for (Shard s : Shards.all()) {
            try (Connection c = s.open(); var st = c.createStatement()) {
                st.execute("SET lock_timeout = '4s'");
                st.execute("TRUNCATE saga_dead_letters");
            }
        }
        // the notifications service's own database · its dead letters live
        // there too (database-per-service applies to failures as well)
        try (Connection c = Notifications.openForRead()) {
            DeadLetter.createTableOn(c);
            try (var st = c.createStatement()) {
                st.execute("SET lock_timeout = '4s'");
                st.execute("TRUNCATE saga_dead_letters");
            }
        }
        Shards.forCustomer(IGOR).createCustomer(IGOR, "igor");
        Shards.forCustomer(COCO).createCustomer(COCO, "coco");
        Shards.forCustomer(IGOR).transferLocal(UUID.randomUUID(), Shard.WORLD, IGOR, eur("500.00"));
        Shards.forCustomer(COCO).transferLocal(UUID.randomUUID(), Shard.WORLD, COCO, eur("500.00"));
    }

    // ------------------------------------------------------------------
    /**
     * The applier used to catch Exception, print it and carry on, with the
     * offset auto-committed underneath it. This is the behavioural half of the
     * fix: a record that will not go through is retried, then RECORDED where
     * it can be counted and re-driven · and the next record still lands,
     * because a parked poison event must not wedge the partition behind it.
     */
    @Test
    @DisplayName("lesson 1: a poisoned event parks after real retries · and the NEXT event still gets through")
    void lesson1_poisonParksAndTheQueueMoves() throws Exception {
        UUID poisonTx = UUID.randomUUID();
        String poison = "{\"type\":\"transfer.departed\",\"txId\":\"" + poisonTx
                + "\",\"from\":" + IGOR + ",\"to\":" + COCO + ",\"amount\":\"sixty euros\"}";

        ShardApplier.deliver(poison);

        List<DeadLetter.Entry> dead;
        try (Connection c = Shards.forCustomer(COCO).open()) {
            dead = DeadLetter.all(c);
        }
        assertEquals(1, dead.size(),
                "a record that will not go through is kept where it can be counted and re-driven, "
                + "not printed to stderr and dropped with its offset already committed");
        assertEquals("transfer.departed:" + poisonTx, dead.get(0).eventKey(),
                "keyed by the event, so a redelivery cannot inflate the count");
        assertTrue(dead.get(0).attempts() >= ShardApplier.ATTEMPTS,
                "after being genuinely retried · a transient blip must not park on the first throw");

        // the queue is not wedged: the next departure still lands
        UUID tx = UUID.randomUUID();
        Shards.forCustomer(IGOR).depart(tx, IGOR, COCO, eur("30.00"));
        ShardApplier.deliver(Fixtures.outboxEvent(Shards.s(EU), "departed:" + tx).payload());
        assertEquals(0, eur("530.00").compareTo(Shards.s(UK).balance(COCO)), "the next event landed");
        assertEquals(0, BigDecimal.ZERO.compareTo(Shards.inFlight()), "and the pipe drained");

        // keyed, not appended · the same poison delivered again is one stuck
        // step that has been noticed twice, not two stuck steps
        ShardApplier.deliver(poison);
        try (Connection c = Shards.forCustomer(COCO).open()) {
            assertEquals(1, DeadLetter.all(c).size(),
                    "'how many are stuck', not 'how many times we noticed'");
        }
    }

    // ------------------------------------------------------------------
    /**
     * Same doctrine, other consumer. A notification is not money, but a
     * consumer that silently drops events is a pattern, and patterns spread
     * by example · both readers of the payments topic own their offsets and
     * park their failures, or neither reliably does.
     */
    @Test
    @DisplayName("lesson 2: the notifications consumer parks failures in ITS OWN database · and keeps consuming")
    void lesson2_notificationsParksInItsOwnDatabase() throws Exception {
        UUID tx = UUID.randomUUID();
        String payload = "{\"type\":\"payment.completed\",\"txId\":\"" + tx
                + "\",\"from\":" + IGOR + ",\"to\":" + COCO + ",\"amount\":\"30.00\"}";

        // a null key: the schema refuses it (event_key is the primary key),
        // which stands in for any failure the handler cannot resolve alone
        NotificationsConsumer.deliver(null, payload);

        List<DeadLetter.Entry> dead;
        try (Connection c = Notifications.openForRead()) {
            dead = DeadLetter.all(c);
        }
        assertEquals(1, dead.size(),
                "the step that would not go through is recorded in the consumer's own database");
        assertTrue(dead.get(0).attempts() >= NotificationsConsumer.ATTEMPTS, "after real retries");

        // and the consumer is not wedged: the next event notifies. Checked by
        // key, not by count · a live bank instance on this compose stack may
        // be writing notifications of its own at any moment (see Fixtures).
        NotificationsConsumer.deliver(tx.toString(), payload);
        try (Connection c = Notifications.openForRead();
             var ps = c.prepareStatement("SELECT 1 FROM notifications WHERE event_key = ?")) {
            ps.setString(1, tx.toString());
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next(), "the next event still gets through");
            }
        }
    }

    // ------------------------------------------------------------------
    /**
     * THE HOLE ITSELF. The event reached Kafka (the outbox row is marked
     * published), the consumer lost it, and before the sweeper existed that
     * was the end of the story: thirty euros in IN_TRANSIT forever, invisible
     * to sum-zero (the depart balances), invisible to drift (the cache
     * agrees), invisible to the dead letters (nothing ever failed HERE).
     */
    @Test
    @DisplayName("lesson 3: a departure whose arrival was LOST is found, republished, and the money lands exactly once")
    void lesson3_strandedDepartureIsRepublishedAndLandsOnce() throws Exception {
        UUID tx = UUID.randomUUID();
        Shards.forCustomer(IGOR).depart(tx, IGOR, COCO, eur("30.00"));

        // the relay shipped it and the applier dropped it · from the ledger's
        // point of view the event simply evaporated
        try (Connection c = Shards.s(EU).open();
             var ps = c.prepareStatement("UPDATE outbox SET published_at = now() WHERE key = ?")) {
            ps.setString(1, "departed:" + tx);
            assertEquals(1, ps.executeUpdate(), "the fixture must mark the real departure published");
        }
        assertEquals(0, eur("30.00").compareTo(Shards.inFlight()), "the money is stranded in the pipe");

        Sweeper.Report report = Sweeper.sweepOnce(0);
        assertEquals(1, report.found(), "the sweeper sees the stranded saga");
        assertEquals(1, report.republished(), "and re-arms the at-least-once machinery");

        // the re-published event: same deterministic key, same payload,
        // appended for the relay to pick up. Counted over ALL rows, published
        // or not · a live relay on this compose stack may ship the retry
        // within milliseconds, and that is the design working, not a flake.
        List<Outbox.Event> copies;
        try (Connection c = Shards.s(EU).open()) {
            copies = Fixtures.allOutboxOn(c).stream()
                    .filter(e -> ("departed:" + tx).equals(e.key())).toList();
        }
        assertEquals(2, copies.size(), "the original and exactly one retry, not a flood");
        assertEquals(copies.get(0).payload(), copies.get(1).payload(),
                "the retry is the original's own bytes · the sweeper invents nothing");

        // the redelivery lands · and lands ONCE, because arrive() claims the
        // txId inside the effect transaction
        ShardApplier.deliver(copies.get(1).payload());
        ShardApplier.deliver(copies.get(1).payload());   // Kafka will do this eventually
        assertEquals(0, eur("530.00").compareTo(Shards.s(UK).balance(COCO)), "the money arrived");
        assertEquals(0, BigDecimal.ZERO.compareTo(Shards.inFlight()), "the pipe is drained");

        // the repaired saga is repaired · a second sweep has nothing to say
        assertEquals(0, Sweeper.sweepOnce(0).found(), "nothing left to find");

        // and the repair was COUNTED. A sweeper that repairs silently hides
        // the failure rate it exists to expose.
        String metrics = Metrics.scrape();
        assertTrue(metrics.contains("kind=\"sweep_republished\""),
                "the dashboard must see how often the pipe loses events: " + firstSweepLines(metrics));
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 4: a saga that completed · or bounced · is NOT touched. The sweeper repairs, it does not meddle")
    void lesson4_completedAndBouncedSagasAreLeftAlone() throws Exception {
        // the happy path, completed normally
        UUID done = UUID.randomUUID();
        Shards.forCustomer(IGOR).depart(done, IGOR, COCO, eur("30.00"));
        ShardApplier.deliver(Fixtures.outboxEvent(Shards.s(EU), "departed:" + done).payload());
        assertEquals(0, eur("530.00").compareTo(Shards.s(UK).balance(COCO)));

        // the unhappy path, compensated: destination does not exist
        UUID bounced = UUID.randomUUID();
        Shards.forCustomer(IGOR).depart(bounced, IGOR, 99, eur("20.00"));
        ShardApplier.deliver(Fixtures.outboxEvent(Shards.s(EU), "departed:" + bounced).payload());
        assertEquals(0, eur("470.00").compareTo(Shards.s(EU).balance(IGOR)), "the bounce refunded igor");
        assertEquals(0, BigDecimal.ZERO.compareTo(Shards.inFlight()), "nothing in the pipe");

        int outboxBefore = outboxRows(Shards.s(EU));
        Sweeper.Report report = Sweeper.sweepOnce(0);
        assertEquals(0, report.found(), "an arrival claim or a refund claim closes the case");
        assertEquals(0, report.republished(), "so nothing is republished");
        assertEquals(outboxBefore, outboxRows(Shards.s(EU)), "and the outbox is untouched");
    }

    // ------------------------------------------------------------------
    /**
     * A departure whose event has NOT reached Kafka yet is the relay's job,
     * not the sweeper's: the unpublished row IS the pending retry, and
     * appending a copy next to it would just double the queue every pass.
     * The sweeper counts it as stranded · past the threshold, a silent relay
     * is exactly as alarming as a lost arrival · but defers the repair.
     *
     * Staged with a PROBE rather than a real departure: a live bank on this
     * compose stack (see Fixtures) would ship a real igor->coco event and
     * complete the saga mid-assertion. The probe's claim is a real 'depart'
     * claim, its outbox row a real unpublished row · but its payload type is
     * one no applier acts on, so the state holds still long enough to look at.
     */
    @Test
    @DisplayName("lesson 5: an unpublished departure is counted but NOT duplicated · the relay already owes that delivery")
    void lesson5_relayBacklogIsNotDuplicated() throws Exception {
        UUID tx = UUID.randomUUID();
        try (Connection c = Shards.s(EU).open()) {
            try (var ps = c.prepareStatement("INSERT INTO transactions(id, kind) VALUES (?, 'depart')")) {
                ps.setObject(1, tx);
                ps.executeUpdate();
            }
            try (var ps = c.prepareStatement(
                    "INSERT INTO outbox(topic, key, payload) VALUES ('payments', ?, ?)")) {
                ps.setString(1, "departed:" + tx);
                ps.setString(2, "{\"type\":\"sweeper.probe\",\"txId\":\"" + tx + "\"}");
                ps.executeUpdate();
            }
        }

        // PIN the row unpublished while the sweeper looks. A live relay ships
        // any committed unpublished row within its 500ms poll, and this
        // lesson is ABOUT the unpublished state · so the row is held FOR
        // UPDATE, which blocks the relay's mark (an UPDATE) while leaving the
        // sweeper's plain reads free. If the relay wins the tiny window
        // between commit and lock, un-publish and take the lock again.
        try (Connection lock = Shards.s(EU).open()) {
            lock.setAutoCommit(false);
            for (int attempt = 0; ; attempt++) {
                boolean unpublished;
                try (var ps = lock.prepareStatement(
                        "SELECT published_at FROM outbox WHERE key = ? FOR UPDATE")) {
                    ps.setString(1, "departed:" + tx);
                    try (var rs = ps.executeQuery()) {
                        assertTrue(rs.next(), "the probe row must exist");
                        unpublished = rs.getTimestamp(1) == null;
                    }
                }
                if (unpublished) break;   // locked, and the relay cannot mark it now
                assertTrue(attempt < 5, "could not stage an unpublished row against the live relay");
                try (var ps = lock.prepareStatement(
                        "UPDATE outbox SET published_at = NULL WHERE key = ?")) {
                    ps.setString(1, "departed:" + tx);
                    ps.executeUpdate();
                }
                lock.commit();            // release, then immediately re-acquire above
            }

            Sweeper.Report report = Sweeper.sweepOnce(0);
            assertEquals(1, report.found(), "past the threshold this saga IS stranded");
            assertEquals(0, report.republished(), "but the unpublished row is already the retry");
            lock.rollback();
        }
        assertEquals(1, outboxRowsFor(Shards.s(EU), "departed:" + tx),
                "one departure, one row · the sweeper must not turn a slow relay into a flood");
    }

    // ------------------------------------------------------------------
    /**
     * A source-level assertion, for the same reason MetricsCoverageLessonTest
     * uses them: the failure being guarded against is an OMISSION. A sweeper
     * that exists and is not started protects nothing, and no behavioural
     * test of Sweeper itself can notice that.
     */
    @Test
    @DisplayName("lesson 6: the sweeper is wired in Main, with the other background loops · built but not started is not built")
    void lesson6_theSweeperActuallyRuns() throws Exception {
        String main = Files.readString(Path.of("src/main/java/dev/minibank/ledger/Main.java"));
        assertTrue(main.contains("Sweeper.start("),
                "Main must start the sweeper alongside the relays and consumers");
        String sweeper = Files.readString(Path.of("src/main/java/dev/minibank/ledger/Sweeper.java"));
        assertTrue(sweeper.contains("Metrics."),
                "and the sweeper must report what it finds · a silent repair hides the failure rate");
    }

    // ------------------------------------------------------------------ helpers
    private static BigDecimal eur(String v) {
        return new BigDecimal(v);
    }

    private static int outboxRows(Shard s) throws Exception {
        try (Connection c = s.open()) {
            return Fixtures.allOutboxOn(c).size();
        }
    }

    private static int outboxRowsFor(Shard s, String key) throws Exception {
        try (Connection c = s.open()) {
            return (int) Fixtures.allOutboxOn(c).stream().filter(e -> key.equals(e.key())).count();
        }
    }

    private static String firstSweepLines(String metrics) {
        return metrics.lines().filter(l -> l.contains("sweep")).reduce("", (a, b) -> a + "\n" + b);
    }
}
