package dev.minibank.broker;

import dev.minibank.ledger.Outbox;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ONE WRITE PATH, AND THE INVARIANT THAT PROVES IT.
 *
 * The broker owns the POSITION · quantity, cost basis, orders, fills. The
 * ledger owns MONEY and CUSTODY · the cash leg, and the asset balance as a
 * double-entry consequence of settlement. The ledger never originates an
 * asset move. That division only means anything if something checks it, and
 * for a long time nothing did: the bank had a second way to acquire an
 * asset, it wrote straight into the ledger, and every audit the bank ran was
 * green the whole time. Sum-zero was green. Drift was green. The broker's own
 * projection audit was green. All three were looking at one book.
 *
 *   lesson 1  the pipeline agrees with itself · both books, one number
 *   lesson 2  THE ESCAPE IS CAUGHT · a write outside the pipeline diverges,
 *             and this is the test that fails if the reconciliation is gutted
 *   lesson 3  the in-flight window is TOLERATED, not fudged · and the
 *             half of it that looks like a gap and is not
 *   lesson 4  a stalled saga is not excused forever · the age alarm
 *   lesson 5  the backfill repairs the divergence, from the ledger's own facts
 *   lesson 6  running it twice changes nothing · idempotent by construction
 *   lesson 7  and it moves NO money · no outbox row, no second settlement
 *
 * Requires: docker compose up -d
 */
class SingleWritePathLessonTest {

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
            }
            s.createSchema();
        }
        dev.minibank.ledger.Fixtures.resetBrokerDb();
        Catalog.seed();
        VENUE.reset();

        Shards.forCustomer(IGOR).createCustomer(IGOR, "igor");
        Products.ensureFor(IGOR);
        Shards.forCustomer(IGOR).transferLocal(UUID.randomUUID(), Shard.WORLD, IGOR, dec("10000.00"));
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 1: the pipeline agrees with itself · one order, one fill, one settlement, two books, one number")
    void lesson1_thePipelineReconciles() throws Exception {
        VENUE.fillAt("50000.00");
        broker.place("o1", IGOR, "BTC", "buy", null, dec("500.00"), "market", null);
        settleAll();
        drainSettlements();

        assertEquals(0, dec("0.01").compareTo(ledgerAsset("BTC")), "the ledger took custody");
        assertEquals(0, dec("0.01").compareTo(brokerQty("BTC")), "the broker holds the position");
        assertEquals(List.of(), Reconciliation.divergences(),
                "and the reconciliation has nothing to say · this is the healthy state");
    }

    // ------------------------------------------------------------------
    /**
     * THE TEST THE WHOLE INVARIANT EXISTS FOR.
     *
     * It performs a trade the OLD way · straight into the ledger, no order,
     * no fill, nothing the broker can see · and asserts the reconciliation
     * catches it. If someone reverts the reconciliation, weakens it to a
     * tolerance, or reintroduces a second write path, this is what goes red.
     * Every other lesson here can pass on a system with no invariant at all.
     */
    @Test
    @DisplayName("lesson 2: a write that escapes the pipeline is CAUGHT · the old path, and the alarm that sees it")
    void lesson2_anEscapedWriteIsCaught() throws Exception {
        assertEquals(List.of(), Reconciliation.divergences(), "clean to start with");

        // the retired path, used deliberately: 500 EUR of BTC at 50k, written
        // into the ledger and nowhere else
        Products.tradeWithoutBroker(UUID.randomUUID(), IGOR, "BTC", true,
                dec("500.00"), dec("50000.00"));

        assertEquals(0, dec("0.01").compareTo(ledgerAsset("BTC")), "the ledger credited it");
        assertEquals(0, BigDecimal.ZERO.compareTo(brokerQty("BTC")), "the broker never heard about it");
        assertEquals(0, fillCount(), "no fill exists, because no order was ever placed");

        // every audit the bank had BEFORE this one is green on this data ·
        // that is precisely why a third one was needed
        assertEquals(List.of(), Broker.audit(),
                "the broker's projection rebuilds perfectly from the fills it has");

        List<Reconciliation.Divergence> d = Reconciliation.divergences();
        assertEquals(1, d.size(), "the reconciliation is the only thing that sees it");
        Reconciliation.Divergence one = d.get(0);
        assertEquals(IGOR, one.customerId());
        assertEquals("BTC", one.symbol());
        assertEquals(0, dec("0.01").compareTo(one.gap()),
                "and the gap IS the escaped quantity · not an approximation of it");
        assertEquals(1, one.gap().signum(),
                "signed, because ledger-ahead and broker-ahead mean opposite things");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 3: the in-flight window is tolerated, not fudged · including the half that looks like a gap")
    void lesson3_inFlightIsTolerated() throws Exception {
        VENUE.fillAt("50000.00");
        broker.place("o1", IGOR, "BTC", "buy", null, dec("500.00"), "market", null);

        // PHASE ONE · the venue filled, the ledger has not settled. The broker
        // is legitimately ahead by the whole fill.
        assertEquals(0, dec("0.01").compareTo(brokerQty("BTC")), "the position moved at fill time");
        assertEquals(0, BigDecimal.ZERO.compareTo(ledgerAsset("BTC")), "the money has not");
        assertEquals(List.of(), Reconciliation.divergences(),
                "a race is not a break · the gap is exactly the in-flight quantity");

        // PHASE TWO · the ledger HAS settled, but the broker has not yet been
        // told, so the order is still 'filled'. The naive in-flight definition
        // subtracts the fill here too and reports a divergence of -0.01 on two
        // books that agree perfectly. This assertion is what pins that shut.
        settleAll();
        assertEquals(0, dec("0.01").compareTo(ledgerAsset("BTC")), "the ledger has taken custody");
        assertEquals("filled", orderStatus("o1"), "and the broker has not been told yet");
        assertEquals(List.of(), Reconciliation.divergences(),
                "settled-but-unacknowledged is not a divergence · the books already agree");

        drainSettlements();
        assertEquals("settled", orderStatus("o1"));
        assertEquals(List.of(), Reconciliation.divergences(), "and still nothing to report");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 4: an exemption with no floor excuses itself forever · a stalled saga is alarmed on by AGE")
    void lesson4_stalledSettlementsAreVisible() throws Exception {
        VENUE.fillAt("50000.00");
        broker.place("o1", IGOR, "BTC", "buy", null, dec("500.00"), "market", null);

        // young: in-flight, and that is fine
        assertEquals(List.of(), Reconciliation.stalled(java.time.Duration.ofMinutes(5)),
                "a fresh fill is a race, not a stall");

        // the same order, judged against a window it is older than · this is
        // the alarm that stops "filled but not settled" from being a permanent
        // excuse for a permanently wrong book
        assertEquals(1, Reconciliation.stalled(java.time.Duration.ZERO).size(),
                "an order that never settles must eventually be somebody's problem");

        settleAll();
        drainSettlements();
        assertEquals(List.of(), Reconciliation.stalled(java.time.Duration.ZERO),
                "and settling clears it");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 5: the backfill repairs the divergence · reconstructed from the ledger's own facts")
    void lesson5_backfillRepairs() throws Exception {
        Products.tradeWithoutBroker(UUID.randomUUID(), IGOR, "AAPL", true,
                dec("1000.00"), dec("200.00"));
        assertEquals(1, Reconciliation.divergences().size(), "the divergence is there to repair");

        Backfill.Report r = Backfill.run();
        assertEquals(1, r.fillsCreated(), "one trade, one reconstructed fill");
        assertEquals(List.of(), Reconciliation.divergences(), "and the books agree afterwards");

        // the reconstruction is not a fudged quantity · it carries a real
        // price, recovered from the cash and asset legs of the same recorded
        // transaction, so the cost basis is right and not merely present
        Broker.Position p = position("AAPL");
        assertEquals(0, dec("5.00000000").compareTo(p.qty()), "1000 at 200");
        assertEquals(0, dec("1000.00").compareTo(p.costBasis()),
                "and it cost what the ledger says it cost");
        assertEquals(0, dec("200.00").compareTo(p.averageCost()));

        // and the repair leaves the projection audit able to do its job ·
        // this is the whole argument for reconstructing fills rather than
        // setting the quantity directly
        assertEquals(List.of(), Broker.audit(),
                "the position still rebuilds from its fills · no permanent drift was introduced");
        assertEquals("backfill", fillKind(), "and the row says what it is, rather than posing as a trade");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 6: running it twice changes nothing · a repair that is not idempotent is a second bug")
    void lesson6_backfillIsIdempotent() throws Exception {
        Products.tradeWithoutBroker(UUID.randomUUID(), IGOR, "AAPL", true,
                dec("1000.00"), dec("200.00"));
        Backfill.Report first = Backfill.run();
        assertEquals(1, first.fillsCreated());

        BigDecimal qtyAfterFirst = brokerQty("AAPL");
        BigDecimal basisAfterFirst = position("AAPL").costBasis();

        Backfill.Report second = Backfill.run();
        assertEquals(0, second.fillsCreated(), "nothing left to reconstruct");
        assertFalse(second.changedAnything(), "a no-op, and it says so");
        assertEquals(1, fillCount(), "one fill, not two · the deterministic id is the gate");
        assertEquals(0, qtyAfterFirst.compareTo(brokerQty("AAPL")), "the position did not double");
        assertEquals(0, basisAfterFirst.compareTo(position("AAPL").costBasis()),
                "and neither did what it cost");
        assertEquals(List.of(), Reconciliation.divergences());

        // and on books that already agree it is a no-op from the first run
        Backfill.Report onClean = Backfill.run();
        assertFalse(onClean.changedAnything(), "safe to run at boot, forever");
    }

    // ------------------------------------------------------------------
    /**
     * The failure mode that would have been expensive: the normal fill path
     * writes an order.filled outbox row in the same commit as the fill. A
     * backfill that reused it would publish every reconstructed fill, the
     * ledger would consume them, and every one of these trades would settle a
     * SECOND time · charging the customer again for something they already
     * bought.
     */
    @Test
    @DisplayName("lesson 7: the repair moves NO money · no event, no second settlement, no second charge")
    void lesson7_backfillMovesNoMoney() throws Exception {
        Products.tradeWithoutBroker(UUID.randomUUID(), IGOR, "AAPL", true,
                dec("1000.00"), dec("200.00"));

        BigDecimal cashBefore = Shards.forCustomer(IGOR).balance(IGOR);
        BigDecimal assetBefore = ledgerAsset("AAPL");
        int brokerOutboxBefore = brokerOutboxCount();
        int ledgerTxBefore = ledgerTransactionCount();

        Backfill.run();

        assertEquals(0, cashBefore.compareTo(Shards.forCustomer(IGOR).balance(IGOR)),
                "not a cent moved");
        assertEquals(0, assetBefore.compareTo(ledgerAsset("AAPL")), "and not a share");
        assertEquals(brokerOutboxBefore, brokerOutboxCount(),
                "NO outbox row · this is the line that stops a double settlement");
        assertEquals(ledgerTxBefore, ledgerTransactionCount(),
                "and the ledger gained no transaction · the repair only ever read it");

        // prove the negative properly: drain what the relay would ship and
        // check nothing new settles
        settleAll();
        assertEquals(0, cashBefore.compareTo(Shards.forCustomer(IGOR).balance(IGOR)),
                "there was nothing to publish, so there was nothing to settle twice");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 8: the two books can disagree the OTHER way too, and that is the worse one")
    void lesson8_signIsMeaningful() throws Exception {
        // a broker position the ledger never funded · the mirror of lesson 2.
        // Written directly, because no code path produces it any more, which
        // is the point: if one ever does, this is the shape it will take.
        try (Connection c = BrokerDb.open()) {
            Broker.store(c, new Broker.Position(IGOR, "BTC",
                    dec("0.02000000"), dec("1000.00000000"), BigDecimal.ZERO));
        }
        List<Reconciliation.Divergence> d = Reconciliation.divergences();
        assertEquals(1, d.size());
        assertEquals(-1, d.get(0).gap().signum(),
                "broker ahead of the ledger · a position that was never paid for");
        assertNotEquals(d.get(0).gap().signum(), 1,
                "and it must not be reported with the same sign as an escaped ledger write");
    }

    // ------------------------------------------------------------------
    private static BigDecimal dec(String v) { return new BigDecimal(v); }

    private static BigDecimal ledgerAsset(String symbol) throws Exception {
        long off = "BTC".equals(symbol) ? Products.BTC : Products.AAPL;
        return Shards.forCustomer(IGOR).balance(IGOR + off);
    }

    private static BigDecimal brokerQty(String symbol) throws Exception {
        return position(symbol).qty();
    }

    private static Broker.Position position(String symbol) throws Exception {
        try (Connection c = BrokerDb.open()) {
            return Broker.positionOn(c, IGOR, symbol);
        }
    }

    private static String orderStatus(String clientOrderId) throws Exception {
        try (Connection c = BrokerDb.open()) {
            return Broker.byClientId(c, clientOrderId).status();
        }
    }

    private static int fillCount() throws Exception {
        return count(BrokerDb.open(), "SELECT count(*) FROM fills");
    }

    private static String fillKind() throws Exception {
        try (Connection c = BrokerDb.open();
             var ps = c.prepareStatement("SELECT kind FROM fills LIMIT 1");
             var rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static int brokerOutboxCount() throws Exception {
        return count(BrokerDb.open(), "SELECT count(*) FROM outbox");
    }

    private static int ledgerTransactionCount() throws Exception {
        return count(Shards.forCustomer(IGOR).open(), "SELECT count(*) FROM transactions");
    }

    private static int count(Connection open, String sql) throws Exception {
        try (Connection c = open; var ps = c.prepareStatement(sql); var rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** hand every unshipped broker event to the ledger · what Kafka would do */
    private static void settleAll() throws Exception {
        try (Connection c = BrokerDb.open()) {
            for (BrokerDb.Event e : BrokerDb.pollUnpublished(c, 50)) {
                Settlement.handle(e.payload());
                BrokerDb.markPublished(c, e.id());
            }
        }
    }

    /** and hand the ledger's answers back to the broker */
    private static void drainSettlements() throws Exception {
        Shard home = Shards.forCustomer(IGOR);
        try (Connection c = home.open()) {
            for (Outbox.Event e : Outbox.pollUnpublishedOn(c, 50)) {
                if (Settlement.TOPIC_SETTLEMENTS.equals(e.topic()))
                    SettlementConsumer.handle(broker, e.payload());
                Outbox.markPublishedOn(c, e.id(), java.time.Instant.now());
            }
        }
    }
}
