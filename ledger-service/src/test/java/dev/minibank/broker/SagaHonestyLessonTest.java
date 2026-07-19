package dev.minibank.broker;

import dev.minibank.ledger.DeadLetter;
import dev.minibank.ledger.Products;
import dev.minibank.ledger.Settlement;
import dev.minibank.ledger.Shard;
import dev.minibank.ledger.Shards;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WHAT THE AUDIT COULD NOT SEE · five ways this saga lied by omission.
 *
 * SettlementSagaLessonTest proves the saga's happy path and its compensation.
 * Every lesson here is a case where that machinery reported success, or
 * reported nothing at all, while the two books disagreed about money. They
 * share a shape worth naming: each one is an EXEMPTION that never expired, a
 * gate on the wrong key, or a failure with nowhere to be recorded. None of
 * them was visible to an invariant, which is why each needed a bug to find it
 * and a test to keep it found.
 *
 *   lesson 1  the in-flight exemption EXPIRES · a stalled fill is a divergence
 *   lesson 2  a refused settlement is refused DURABLY · a replay moves nothing
 *   lesson 3  a saga step that cannot complete is RECORDED, not printed
 *   lesson 4  the venue's commission is MONEY · it moves, or the books differ
 *   lesson 5  the repair rebuilds a projection it did not write this run
 *
 * Requires: docker compose up -d
 */
class SagaHonestyLessonTest {

    static final long IGOR = 10;
    static final BrokerLessonTest.StubVenue VENUE = new BrokerLessonTest.StubVenue();
    static Broker broker;

    @BeforeAll
    static void boot() throws Exception {
        Shards.boot(
                System.getenv().getOrDefault("MINIBANK_SHARD0_URL", "jdbc:postgresql://localhost:5434/minibank"),
                System.getenv().getOrDefault("MINIBANK_SHARD1_URL", "jdbc:postgresql://localhost:5435/minibank"),
                "minibank", "minibank", 5);
        for (Shard s : Shards.all()) s.createSchema();
        BrokerDb.createOwnDatabase();
        Catalog.seed();
        broker = new Broker(VENUE);
    }

    @BeforeEach
    void freshEverything() throws Exception {
        for (Shard s : Shards.all()) {
            try (Connection c = s.open(); var st = c.createStatement()) {
                st.execute("SET lock_timeout = '4s'");
                st.execute("TRUNCATE entries, transactions, outbox, accounts CASCADE");
                st.execute("TRUNCATE asset_accounts");
                st.execute("TRUNCATE saga_dead_letters");
            }
            s.createSchema();
        }
        try (Connection c = BrokerDb.open(); var st = c.createStatement()) {
            st.execute("SET lock_timeout = '4s'");
            st.execute("TRUNCATE fills, orders, positions, watchlist, account_link, outbox");
            st.execute("TRUNCATE saga_dead_letters");
        }
        Catalog.seed();
        VENUE.reset();

        Shards.forCustomer(IGOR).createCustomer(IGOR, "igor");
        Products.ensureFor(IGOR);
        Shards.forCustomer(IGOR).transferLocal(UUID.randomUUID(), Shard.WORLD, IGOR, dec("1000.00"));
    }

    // ------------------------------------------------------------------
    /**
     * The in-flight term subtracts fills the broker has booked and the ledger
     * has not settled, because between those two commits the broker is
     * legitimately ahead. The word doing the work is BETWEEN · it describes a
     * window, and a window has a far edge.
     *
     * It had none. "Every fill whose order is still 'filled'" excused itself
     * for as long as the order stayed there, and an order stays there forever
     * when the step that should move it throws · exactly what happens when a
     * compensation tries to give back units the customer has already sold.
     * expect = broker - inFlight came to zero, the ledger held zero, and the
     * only audit wired to a metric on the healthy path reported nothing at
     * all. A position nobody paid for was invisible.
     */
    @Test
    @DisplayName("lesson 1: the in-flight exemption EXPIRES · a fill stuck 'filled' becomes a divergence")
    void lesson1_inFlightExemptionExpires() throws Exception {
        VENUE.fillAt("50000.00");
        Broker.Order order = buy("s1", "BTC", "500.00");

        // the fill is booked, the ledger has not settled it, and the order is
        // seconds old · this gap is the legitimate one
        assertEquals(0, dec("0.01").compareTo(position("BTC").qty()), "the venue filled it");
        assertTrue(divergencesFor("BTC").isEmpty(),
                "a young unsettled fill is a window, not a fault");

        // the same books, with the window long closed. Nothing about the
        // MONEY changed here · only how long it has been wrong.
        ageOrder(order, "10 minutes");

        List<String> after = divergencesFor("BTC");
        assertEquals(1, after.size(),
                "past the grace, a fill the ledger never settled is a divergence, not an exemption");
        assertTrue(after.get(0).contains("broker 0.01000000"),
                "and it names the position the customer never paid for: " + after.get(0));

        // stalled() always knew · but it is a separate list, it is not what
        // the divergence metric reads, and it says nothing for five minutes
        assertFalse(Reconciliation.stalled(java.time.Duration.ofMinutes(5)).isEmpty(),
                "the age alarm agrees, which is the point: two witnesses, not one excuse");
    }

    // ------------------------------------------------------------------
    /**
     * The refusal was idempotent against a second REJECTION and wide open to a
     * second SETTLEMENT, because the two were gated on different keys.
     * settleFill claims the fill id and rolls that claim back together with
     * the refusal; the refusal then claimed a DERIVED id. So the fill id was
     * still unclaimed, Settlement.handle calls settleFill unconditionally on
     * every delivery, and Kafka is at-least-once by this saga's own admission.
     */
    @Test
    @DisplayName("lesson 2: a refused settlement is refused DURABLY · replaying the fill moves nothing")
    void lesson2_refusalSurvivesAReplay() throws Exception {
        VENUE.fillAt("50000.00");
        buy("s1", "BTC", "5000.00");          // he has 1000
        String fill = filledEvent();

        settleAll();                          // the ledger refuses
        drainSettlements();                   // the broker takes the position back

        assertEquals(0, BigDecimal.ZERO.compareTo(position("BTC").qty()), "position unwound");
        assertEquals(0, BigDecimal.ZERO.compareTo(ledgerAsset("BTC")), "and the ledger holds none");

        // THE CUSTOMER IS THEN TOPPED UP · a deposit, a payday, anything. The
        // order still says 'rejected' and the broker still holds nothing.
        Shards.forCustomer(IGOR).transferLocal(UUID.randomUUID(), Shard.WORLD, IGOR, dec("10000.00"));
        assertEquals(0, dec("11000.00").compareTo(cash()), "funded now");

        // a rebalance, a restart on an uncommitted offset, a manual replay
        Settlement.handle(fill);
        Settlement.handle(fill);

        assertEquals(0, dec("11000.00").compareTo(cash()),
                "the money must NOT move for an order that was already unwound");
        assertEquals(0, BigDecimal.ZERO.compareTo(ledgerAsset("BTC")),
                "and no bitcoin may appear against a position the broker gave back");
        assertEquals(0, BigDecimal.ZERO.compareTo(position("BTC").qty()), "broker unchanged");
        assertEquals(List.of(), Reconciliation.divergences(), "the books still agree");
    }

    // ------------------------------------------------------------------
    /**
     * Both saga consumers caught Exception, printed it and carried on, and
     * neither set enable.auto.commit, so it defaulted to true and the offset
     * advanced anyway. A compensation that threw was therefore not retried,
     * not recorded and not reported · the only trace was a line on stderr.
     *
     * A compensation CAN legitimately be impossible: reversing a buy means
     * selling those units back, and the customer may have already sold them.
     * That is precisely the case that must be recorded rather than shrugged
     * off, because it is the one where the two books stay apart.
     */
    @Test
    @DisplayName("lesson 3: a saga step that cannot complete is RECORDED, not printed and forgotten")
    void lesson3_failedStepIsDurable() throws Exception {
        VENUE.fillAt("50000.00");
        buy("s1", "BTC", "500.00");
        settleAll();
        drainSettlements();
        UUID boughtFill = firstFillId();

        // he sells the lot · the units the buy would need to give back are gone
        sell("s2", "BTC", "0.01");
        settleAll();
        drainSettlements();
        assertEquals(0, BigDecimal.ZERO.compareTo(position("BTC").qty()), "flat");

        // a late rejection for the ORIGINAL buy · the compensation cannot run
        String impossible = "{\"type\":\"trade.rejected\",\"fillId\":\"" + boughtFill
                + "\",\"customer\":" + IGOR + ",\"reason\":\"late refusal\"}";
        SettlementConsumer.deliver(broker, impossible);

        List<DeadLetter.Entry> dead;
        try (Connection c = BrokerDb.open()) {
            dead = DeadLetter.all(c);
        }
        assertEquals(1, dead.size(),
                "a step that would not go through is kept where it can be counted and re-driven");
        assertTrue(dead.get(0).error().contains("cannot sell"),
                "and it records WHY, not just that: " + dead.get(0));
        assertTrue(dead.get(0).attempts() >= SettlementConsumer.ATTEMPTS,
                "after being genuinely retried, not abandoned on the first throw");

        // keyed, not appended · a redelivered poison record is one stuck step
        SettlementConsumer.deliver(broker, impossible);
        try (Connection c = BrokerDb.open()) {
            assertEquals(1, DeadLetter.all(c).size(),
                    "'how many are stuck', not 'how many times we noticed'");
        }
    }

    // ------------------------------------------------------------------
    /**
     * The venue's commission was charged to the customer's cost basis and no
     * money ever moved for it. advance() added the fee to basis on a buy;
     * recordFill computed the settlement cash as qty*price with the fee left
     * out. Neither invariant could see the difference: reconciliation compares
     * QUANTITIES, and sum-zero cannot notice an entry that was never written.
     *
     * SimulatedVenue charges 10 bps on every fill and is the default venue, so
     * this was the normal path.
     */
    @Test
    @DisplayName("lesson 4: the venue's commission is MONEY · the cash leg carries it or the books differ")
    void lesson4_theFeeActuallyMoves() throws Exception {
        VENUE.fillAt("50000.00");
        VENUE.withFee("0.50");
        buy("s1", "BTC", "500.00");            // 0.01 BTC at 50k, plus 0.50 fee
        settleAll();

        Broker.Position p = position("BTC");
        assertEquals(0, dec("500.50").compareTo(p.costBasis()),
                "what it cost him includes what the venue took");
        assertEquals(0, dec("499.50").compareTo(cash()),
                "and the SAME number left his account · a fee in the basis with no cash leg "
                + "is a commission nobody collected");
        assertEquals(0, dec("500.50").compareTo(brokerEur()),
                "the other side of that cash leg balances too");

        // the two books now agree about money as well as quantity, which is
        // the property the fee bug quietly broke
        assertEquals(0, p.costBasis().compareTo(dec("1000.00").subtract(cash())),
                "cost basis == money that actually left, to the cent");
        assertEquals(List.of(), Reconciliation.divergences());
        assertEquals(List.of(), Broker.audit(), "and the projection still rebuilds from its fills");
    }

    // ------------------------------------------------------------------
    /**
     * The backfill's idempotency gate and its position-rebuild trigger were
     * the same flag. Fills commit one transaction at a time inside the loop;
     * rebuilds all happen after it. A run interrupted between those phases
     * left committed fills with an unrebuilt projection · and since the fills
     * now existed, every later run reported zero and rebuilt nothing. The
     * repair that exists to fix drift insisted there was nothing to do.
     */
    @Test
    @DisplayName("lesson 5: the repair rebuilds a projection even when THIS run wrote no fill")
    void lesson5_interruptedRepairStillFinishes() throws Exception {
        Products.tradeWithoutBroker(UUID.randomUUID(), IGOR, "AAPL", true,
                dec("1000.00"), dec("200.00"));
        assertTrue(Backfill.run().changedAnything(), "the first run reconstructs the history");
        assertEquals(0, dec("5").compareTo(position("AAPL").qty()), "and derives the position");

        // EXACTLY what an interruption between the two phases leaves behind:
        // the fill is committed, the projection is not
        try (Connection c = BrokerDb.open(); var st = c.createStatement()) {
            st.execute("UPDATE positions SET qty = 0, cost_basis = 0 WHERE customer_id = " + IGOR
                    + " AND symbol = 'AAPL'");
        }
        assertFalse(Broker.audit().isEmpty(), "the projection now disagrees with its own fills");

        Backfill.Report second = Backfill.run();
        assertEquals(0, second.fillsCreated(), "there is nothing left to reconstruct, correctly");
        assertTrue(second.changedAnything(),
                "but there IS a projection to rebuild, and the repair must not report itself done");
        assertEquals(0, dec("5").compareTo(position("AAPL").qty()), "the position is back");
        assertEquals(List.of(), Broker.audit(), "the projection agrees with its fills again");
        assertEquals(List.of(), Reconciliation.divergences(), "and with the ledger");

        // and it is still a no-op once everything agrees
        assertFalse(Backfill.run().changedAnything(), "safe to run at boot, forever");
    }

    // ------------------------------------------------------------------ helpers
    private static BigDecimal dec(String v) { return new BigDecimal(v); }

    private Broker.Order buy(String id, String symbol, String notional) throws Exception {
        return broker.place(id, IGOR, symbol, "buy", null, dec(notional), "market", null);
    }

    private Broker.Order sell(String id, String symbol, String qty) throws Exception {
        return broker.place(id, IGOR, symbol, "sell", dec(qty), null, "market", null);
    }

    private static Broker.Position position(String symbol) throws Exception {
        try (Connection c = BrokerDb.open()) {
            return Broker.positionOn(c, IGOR, symbol);
        }
    }

    private static BigDecimal cash() throws Exception {
        return Shards.forCustomer(IGOR).balance(IGOR);
    }

    private static BigDecimal brokerEur() throws Exception {
        return Shards.forCustomer(IGOR).balance(Shard.BROKER_EUR);
    }

    private static BigDecimal ledgerAsset(String symbol) throws Exception {
        Shard home = Shards.forCustomer(IGOR);
        try (Connection c = home.open();
             var ps = c.prepareStatement(
                     "SELECT a.balance FROM asset_accounts aa JOIN accounts a ON a.id = aa.account_id "
                     + "WHERE aa.customer_id = ? AND aa.symbol = ?")) {
            ps.setLong(1, IGOR);
            ps.setString(2, symbol.toLowerCase());
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO;
            }
        }
    }

    /** Only the rows about this symbol · other fixtures must not decide this test. */
    private static List<String> divergencesFor(String symbol) throws Exception {
        return Reconciliation.divergences().stream()
                .filter(d -> d.customerId() == IGOR && symbol.equals(d.symbol()))
                .map(Object::toString)
                .toList();
    }

    /**
     * Make an order OLD without waiting.
     *
     * The in-flight window is measured from orders.updated_at, so moving that
     * column back is the honest way to ask "what does this audit say once the
     * grace has passed" · nothing about the money is touched.
     */
    private static void ageOrder(Broker.Order o, String interval) throws Exception {
        try (Connection c = BrokerDb.open();
             var ps = c.prepareStatement(
                     "UPDATE orders SET updated_at = now() - ?::interval WHERE id = ?")) {
            ps.setString(1, interval);
            ps.setObject(2, o.id());
            assertEquals(1, ps.executeUpdate(), "the fixture must actually age the order");
        }
    }

    private static UUID firstFillId() throws Exception {
        try (Connection c = BrokerDb.open();
             var ps = c.prepareStatement(
                     "SELECT id FROM fills WHERE kind IS DISTINCT FROM 'compensation' ORDER BY executed_at, id LIMIT 1");
             var rs = ps.executeQuery()) {
            assertTrue(rs.next(), "there should be a fill by now");
            return rs.getObject(1, UUID.class);
        }
    }

    private static String filledEvent() throws Exception {
        try (Connection c = BrokerDb.open();
             var ps = c.prepareStatement(
                     "SELECT payload FROM outbox WHERE key LIKE 'filled:%' ORDER BY id LIMIT 1");
             var rs = ps.executeQuery()) {
            assertTrue(rs.next(), "no fill event was ever written");
            String p = rs.getString(1);
            assertNotNull(p);
            return p;
        }
    }

    private static void settleAll() throws Exception {
        try (Connection c = BrokerDb.open()) {
            for (BrokerDb.Event e : BrokerDb.pollUnpublished(c, 50)) {
                Settlement.handle(e.payload());
                BrokerDb.markPublished(c, e.id());
            }
        }
    }

    private static void drainSettlements() throws Exception {
        Shard home = Shards.forCustomer(IGOR);
        try (Connection c = home.open()) {
            for (dev.minibank.ledger.Outbox.Event e :
                    dev.minibank.ledger.Outbox.pollUnpublishedOn(c, 50)) {
                if (Settlement.TOPIC_SETTLEMENTS.equals(e.topic()))
                    SettlementConsumer.handle(broker, e.payload());
                dev.minibank.ledger.Outbox.markPublishedOn(c, e.id(), java.time.Instant.now());
            }
        }
    }
}
