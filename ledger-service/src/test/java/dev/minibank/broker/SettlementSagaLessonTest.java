package dev.minibank.broker;

import dev.minibank.ledger.Ledger;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE SETTLEMENT SAGA · two services, two databases, one outcome.
 *
 * A fill changes a position (broker) and a balance (ledger). They live in
 * different databases owned by different services, so no transaction spans
 * them and two-phase commit is not on the table. What is left is a saga:
 * local commits chained by events, with a compensation for the day the
 * money says no.
 *
 *   lesson 1  a fill settles into the ledger · four entries, both currencies
 *   lesson 2  the fill id IS the ledger's txId · redelivery moves nothing
 *   lesson 3  the settled event rides the SAME commit as the money
 *   lesson 4  no money, no settlement · and the refusal is durable
 *   lesson 5  COMPENSATION · the position comes back, and history says why
 *   lesson 6  compensating twice would be the classic saga bug · it does not
 *   lesson 7  end to end: the books balance in every currency afterwards
 *
 * Requires: docker compose up -d
 */
class SettlementSagaLessonTest {

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
            }
            s.createSchema();
        }
        try (Connection c = BrokerDb.open(); var st = c.createStatement()) {
            st.execute("SET lock_timeout = '4s'");
            st.execute("TRUNCATE fills, orders, positions, watchlist, account_link, outbox");
        }
        Catalog.seed();
        VENUE.reset();

        Shards.forCustomer(IGOR).createCustomer(IGOR, "igor");
        Products.ensureFor(IGOR);
        Shards.forCustomer(IGOR).transferLocal(UUID.randomUUID(), Shard.WORLD, IGOR, dec("1000.00"));
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 1: a fill settles into the ledger · cash out, asset in, four entries, one commit")
    void lesson1_fillSettles() throws Exception {
        VENUE.fillAt("50000.00");
        buy("s1", "BTC", "500.00");
        settleAll();

        Shard home = Shards.forCustomer(IGOR);
        assertEquals(0, dec("500.00").compareTo(home.balance(IGOR)), "the cash left the customer");
        assertEquals(0, dec("0.01").compareTo(home.balance(IGOR + Products.BTC)),
                "and the bitcoin arrived · 500 at 50k");
        assertEquals(0, dec("500.00").compareTo(home.balance(Shard.BROKER_EUR)),
                "the broker account is the other side of the cash leg");
        assertEquals(0, sumZeroViolations(home), "and every currency still sums to zero on its own");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 2: the fill id IS the ledger's txId · Kafka redelivers, the money does not move twice")
    void lesson2_redeliveryIsANoOp() throws Exception {
        VENUE.fillAt("50000.00");
        buy("s1", "BTC", "500.00");

        String event = filledEvent();
        Settlement.handle(event);
        Settlement.handle(event);          // at-least-once, as promised
        Settlement.handle(event);

        Shard home = Shards.forCustomer(IGOR);
        assertEquals(0, dec("500.00").compareTo(home.balance(IGOR)), "charged once");
        assertEquals(0, dec("0.01").compareTo(home.balance(IGOR + Products.BTC)), "delivered once");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 3: the settled event rides the SAME commit as the money · no lost notification")
    void lesson3_settledEventIsTransactional() throws Exception {
        VENUE.fillAt("50000.00");
        buy("s1", "BTC", "500.00");
        settleAll();

        Outbox.Event settled = ledgerOutbox("settled:");
        assertNotNull(settled, "the answer is written where a crash cannot lose it");
        assertEquals(Settlement.TOPIC_SETTLEMENTS, settled.topic());
        assertTrue(settled.payload().contains("\"type\":\"trade.settled\""));
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 4: no money, no settlement · the refusal is recorded so it is told exactly once")
    void lesson4_insufficientFundsRefuses() throws Exception {
        VENUE.fillAt("50000.00");
        buy("s1", "BTC", "5000.00");        // he has 1000
        settleAll();

        Shard home = Shards.forCustomer(IGOR);
        assertEquals(0, dec("1000.00").compareTo(home.balance(IGOR)), "not a cent moved");
        assertEquals(0, BigDecimal.ZERO.compareTo(home.balance(IGOR + Products.BTC)), "and no bitcoin appeared");

        Outbox.Event rejected = ledgerOutbox("rejected:");
        assertNotNull(rejected, "the broker is told, durably");
        assertTrue(rejected.payload().contains("insufficient funds"));

        // told once, however many times the fill is redelivered
        Settlement.handle(filledEvent());
        Settlement.handle(filledEvent());
        assertEquals(1, ledgerOutboxCount("rejected:"), "one refusal, not three");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 5: COMPENSATION · the money refused, so the position comes back and history says why")
    void lesson5_compensation() throws Exception {
        VENUE.fillAt("50000.00");
        Broker.Order order = buy("s1", "BTC", "5000.00");     // more than he has
        assertEquals(0, dec("0.1").compareTo(position("BTC").qty()), "the venue filled it first");

        settleAll();                                          // ledger refuses
        drainSettlements();                                   // broker compensates

        assertEquals(0, BigDecimal.ZERO.compareTo(position("BTC").qty()),
                "the position is back where it started");
        assertEquals("rejected", reload(order).status());
        assertTrue(reload(order).rejectReason().contains("insufficient funds"),
                "and it says why: " + reload(order).rejectReason());

        // the reversal is RECORDED, not erased · fills are append-only facts
        assertEquals(2, fillCount(), "the fill and its compensation both survive");
        assertEquals(1, fillCount("compensation"), "one of them is labelled for what it is");
        assertEquals(List.of(), Broker.audit(), "and the projection still rebuilds from them");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 6: compensating twice is THE classic saga bug · a redelivered rejection does not")
    void lesson6_compensateOnce() throws Exception {
        VENUE.fillAt("50000.00");
        buy("s1", "BTC", "5000.00");
        settleAll();

        String rejection = ledgerOutbox("rejected:").payload();
        SettlementConsumer.handle(broker, rejection);
        SettlementConsumer.handle(broker, rejection);
        SettlementConsumer.handle(broker, rejection);

        assertEquals(0, BigDecimal.ZERO.compareTo(position("BTC").qty()),
                "back to flat · not short 0.2 BTC he never owned");
        assertEquals(1, fillCount("compensation"), "one compensation, three deliveries");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 7: end to end · buy, settle, sell, settle, and both books agree afterwards")
    void lesson7_roundTrip() throws Exception {
        VENUE.fillAt("50000.00");
        buy("s1", "BTC", "500.00");
        settleAll();
        drainSettlements();

        VENUE.fillAt("60000.00");
        sell("s2", "BTC", "0.01");
        settleAll();
        drainSettlements();

        Shard home = Shards.forCustomer(IGOR);
        // 1000 - 500 + 600 = 1100
        assertEquals(0, dec("1100.00").compareTo(home.balance(IGOR)), "sold 10k higher");
        assertEquals(0, BigDecimal.ZERO.compareTo(home.balance(IGOR + Products.BTC)), "and holds no bitcoin");

        Broker.Position p = position("BTC");
        assertEquals(0, BigDecimal.ZERO.compareTo(p.qty()), "the broker agrees he is flat");
        assertEquals(0, dec("100.00").compareTo(p.realizedPnl()), "and says he made 100 doing it");

        assertEquals(0, sumZeroViolations(home), "every currency sums to zero");
        assertEquals(List.of(), Broker.audit(), "and the position rebuilds from its fills");
    }

    // ------------------------------------------------------------------
    private static BigDecimal dec(String v) { return new BigDecimal(v); }

    private Broker.Order buy(String id, String symbol, String notional) throws Exception {
        return broker.place(id, IGOR, symbol, "buy", null, dec(notional), "market", null);
    }

    private Broker.Order sell(String id, String symbol, String qty) throws Exception {
        return broker.place(id, IGOR, symbol, "sell", dec(qty), null, "market", null);
    }

    private static Broker.Order reload(Broker.Order o) throws Exception {
        try (Connection c = BrokerDb.open()) {
            return Broker.byId(c, o.id());
        }
    }

    private static Broker.Position position(String symbol) throws Exception {
        try (Connection c = BrokerDb.open()) {
            return Broker.positionOn(c, IGOR, symbol);
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
                Outbox.markPublishedOn(c, e.id());
            }
        }
    }

    /** the fill event whatever its published_at · once settleAll has run, the
     *  row is marked, and asserting on "still unpublished" would be a race */
    private static String filledEvent() throws Exception {
        try (Connection c = BrokerDb.open();
             var ps = c.prepareStatement(
                     "SELECT payload FROM outbox WHERE key LIKE 'filled:%' ORDER BY id LIMIT 1");
             var rs = ps.executeQuery()) {
            if (!rs.next()) throw new AssertionError("no fill event was ever written");
            return rs.getString(1);
        }
    }

    /** read the ledger's outbox whatever its published_at · a relay may have shipped it */
    private static Outbox.Event ledgerOutbox(String keyPrefix) throws Exception {
        try (Connection c = Shards.forCustomer(IGOR).open();
             var ps = c.prepareStatement(
                     "SELECT id, topic, key, payload FROM outbox WHERE key LIKE ? ORDER BY id")) {
            ps.setString(1, keyPrefix + "%");
            try (var rs = ps.executeQuery()) {
                return rs.next() ? new Outbox.Event(rs.getLong(1), rs.getString(2),
                        rs.getString(3), rs.getString(4)) : null;
            }
        }
    }

    private static int ledgerOutboxCount(String keyPrefix) throws Exception {
        try (Connection c = Shards.forCustomer(IGOR).open();
             var ps = c.prepareStatement("SELECT count(*) FROM outbox WHERE key LIKE ?")) {
            ps.setString(1, keyPrefix + "%");
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static int fillCount() throws Exception { return fillCount(null); }

    private static int fillCount(String kind) throws Exception {
        String sql = kind == null ? "SELECT count(*) FROM fills"
                                  : "SELECT count(*) FROM fills WHERE kind = ?";
        try (Connection c = BrokerDb.open(); var ps = c.prepareStatement(sql)) {
            if (kind != null) ps.setString(1, kind);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static int sumZeroViolations(Shard s) throws Exception {
        try (Connection c = s.open()) {
            return Ledger.sumZeroViolationsOn(c).size();
        }
    }
}
