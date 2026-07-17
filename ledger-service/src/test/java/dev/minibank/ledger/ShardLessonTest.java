package dev.minibank.ledger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STAGE 5 · SHARDING: WRITES ONLY SCALE BY REDRAWING THE MAP.
 *
 * Replicas are clones (they scale reads). Shards are TERRITORIES: the
 * customers are split across independent databases, and each customer's
 * whole world lives on one of them.
 *
 *   lesson 1  a shard is a different machine · igor does not exist on shard1
 *   lesson 2  same-shard transfers are stage 1 unchanged: plain ACID
 *   lesson 3  cross-shard is a SAGA: depart, honestly in-flight, arrive
 *   lesson 4  Kafka may deliver twice · the money arrives once
 *   lesson 5  the applier fed the outbox's own bytes: the real pipe, end to end
 *   lesson 6  destination missing -> the compensating refund; nothing is lost
 *
 * Requires: docker compose up -d   (shard0 :5434, shard1 :5435)
 */
class ShardLessonTest {

    static final long IGOR = 10, COCO = 11;

    @BeforeAll
    static void boot() throws Exception {
        Shards.boot(
                System.getenv().getOrDefault("MINIBANK_SHARD0_URL", "jdbc:postgresql://localhost:5434/minibank"),
                System.getenv().getOrDefault("MINIBANK_SHARD1_URL", "jdbc:postgresql://localhost:5435/minibank"),
                "minibank", "minibank", 5);
        for (Shard s : Shards.all()) s.createSchema();
    }

    @BeforeEach
    void freshMoney() throws Exception {
        for (Shard s : Shards.all()) {
            try (Connection c = s.open(); var st = c.createStatement()) {
                st.execute("TRUNCATE entries, transactions, outbox, accounts CASCADE");
            }
            s.createSchema();   // system accounts back (world, in_transit)
        }
        Shards.forCustomer(IGOR).createCustomer(IGOR, "igor");
        Shards.forCustomer(COCO).createCustomer(COCO, "coco");
        Shards.forCustomer(IGOR).transferLocal(UUID.randomUUID(), Shard.WORLD, IGOR, eur("500.00"));
        Shards.forCustomer(COCO).transferLocal(UUID.randomUUID(), Shard.WORLD, COCO, eur("500.00"));
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 1: a shard is a different MACHINE · igor exists on his shard and nowhere else")
    void lesson1_customersLiveOnExactlyOneShard() throws Exception {
        assertEquals(0, Shards.forCustomer(IGOR).index, "igor (10, even) routes to shard0");
        assertEquals(1, Shards.forCustomer(COCO).index, "coco (11, odd) routes to shard1");

        assertTrue(Shards.s(0).hasAccount(IGOR), "igor's whole world lives on shard0");
        assertFalse(Shards.s(1).hasAccount(IGOR),
                "and shard1 has never heard of him. Separate database, separate machine: " +
                "no JOIN, no foreign key, no transaction can span the two. " +
                "Every lesson in this file exists because of this line.");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 2: both parties on one shard -> the stage-1 transfer, unchanged. Sharding kept the common case ACID")
    void lesson2_sameShardIsPlainAcid() throws Exception {
        // a top-up: world -> igor. System accounts exist on EVERY shard, so
        // this never crosses anything · full ACID, ordered locks, idempotency.
        var r = Shards.plan(Shard.WORLD, IGOR);
        assertFalse(r.crossShard(), "system accounts take the customer's shard: local by construction");

        assertInstanceOf(Ledger.Ok.class,
                r.source().transferLocal(UUID.randomUUID(), Shard.WORLD, IGOR, eur("100.00")));
        assertEquals(0, eur("600.00").compareTo(Shards.s(0).balance(IGOR)));
        assertEquals(0, sumZero(Shards.s(0)).size(), "books balance");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 3: cross-shard is a SAGA · money is honestly IN FLIGHT between two local ACID transactions")
    void lesson3_crossShardSaga() throws Exception {
        UUID tx = UUID.randomUUID();
        assertTrue(Shards.plan(IGOR, COCO).crossShard(), "igor and coco live on different machines");

        // HALF ONE · depart, on igor's shard, fully ACID:
        assertInstanceOf(Ledger.Ok.class, Shards.s(0).depart(tx, IGOR, COCO, eur("30.00")));

        // the in-between state, stated without embarrassment: igor has paid,
        // coco has not been paid, and the books SAY SO · the money sits in
        // the clearing account. Nothing is wrong here. This is what
        // "eventual consistency" looks like when it is done honestly.
        assertEquals(0, eur("470.00").compareTo(Shards.s(0).balance(IGOR)), "igor already debited");
        assertEquals(0, eur("500.00").compareTo(Shards.s(1).balance(COCO)), "coco not yet credited");
        assertEquals(0, eur("30.00").compareTo(Shards.inFlight()), "the fleet says: 30.00 in the pipe");
        assertEquals(0, sumZero(Shards.s(0)).size(), "shard0 balances ALONE, right now");

        // HALF TWO · arrive, on coco's shard, fully ACID:
        assertInstanceOf(Ledger.Ok.class, Shards.s(1).arrive(tx, COCO, eur("30.00")));

        assertEquals(0, eur("530.00").compareTo(Shards.s(1).balance(COCO)), "coco credited");
        assertEquals(0, BigDecimal.ZERO.compareTo(Shards.inFlight()), "pipe drained: in-flight zero");
        assertEquals(0, sumZero(Shards.s(1)).size(), "shard1 balances too");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 4: the arrival is idempotent · Kafka may deliver five times, the money lands once")
    void lesson4_duplicateArrivalIsHarmless() throws Exception {
        UUID tx = UUID.randomUUID();
        Shards.s(0).depart(tx, IGOR, COCO, eur("30.00"));

        assertInstanceOf(Ledger.Ok.class, Shards.s(1).arrive(tx, COCO, eur("30.00")));
        assertInstanceOf(Ledger.AlreadyProcessed.class, Shards.s(1).arrive(tx, COCO, eur("30.00")),
                "same txId, same shard-local gate as stage 1 · the second delivery bounces off the primary key");
        assertInstanceOf(Ledger.AlreadyProcessed.class, Shards.s(1).arrive(tx, COCO, eur("30.00")));

        assertEquals(0, eur("530.00").compareTo(Shards.s(1).balance(COCO)), "credited exactly once");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 5: the real pipe · the applier is fed the outbox's OWN bytes and the money lands")
    void lesson5_theRealPipe() throws Exception {
        UUID tx = UUID.randomUUID();
        Shards.s(0).depart(tx, IGOR, COCO, eur("42.00"));

        // read the event the depart-transaction committed · these are the
        // exact bytes the relay hands to Kafka and Kafka hands back.
        List<Outbox.Event> events;
        try (Connection c = Shards.s(0).open()) {
            events = Outbox.pollUnpublishedOn(c, 100);
        }
        Outbox.Event departed = events.stream()
                .filter(e -> e.key().equals("departed:" + tx)).findFirst().orElseThrow();

        ShardApplier.handle(departed.payload());   // what the consumer does
        ShardApplier.handle(departed.payload());   // ...and a redelivery

        assertEquals(0, eur("542.00").compareTo(Shards.s(1).balance(COCO)), "landed exactly once");
        assertEquals(0, BigDecimal.ZERO.compareTo(Shards.inFlight()), "settled");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 6: destination doesn't exist · the saga COMPENSATES. Money can be in flight, never in limbo")
    void lesson6_theBounce() throws Exception {
        UUID tx = UUID.randomUUID();
        long nobody = 99;   // routes to shard1; no such account there

        // departure cannot know · the destination lives on another machine.
        // It checks what it CAN check (igor's funds) and commits.
        assertInstanceOf(Ledger.Ok.class, Shards.s(0).depart(tx, IGOR, nobody, eur("30.00")));
        assertEquals(0, eur("470.00").compareTo(Shards.s(0).balance(IGOR)));

        // the applier discovers the truth on shard1 and compensates on shard0
        String payload = "{\"type\":\"transfer.departed\",\"txId\":\"" + tx +
                "\",\"from\":" + IGOR + ",\"to\":" + nobody + ",\"amount\":\"30.00\"}";
        ShardApplier.handle(payload);
        ShardApplier.handle(payload);   // the bounce redelivered · refund is gated too

        assertEquals(0, eur("500.00").compareTo(Shards.s(0).balance(IGOR)), "igor made whole, exactly once");
        assertEquals(0, BigDecimal.ZERO.compareTo(Shards.inFlight()), "nothing left in the pipe");
        assertEquals(0, sumZero(Shards.s(0)).size(), "and the books never lied");
    }

    // ------------------------------------------------------------------
    private static BigDecimal eur(String v) {
        return new BigDecimal(v);
    }

    /** the stage-1 audit, pointed at one shard: SUM(entries) per tx must be 0 */
    private static List<UUID> sumZero(Shard s) throws Exception {
        try (Connection c = s.open()) {
            return Ledger.sumZeroViolationsOn(c);
        }
    }
}
