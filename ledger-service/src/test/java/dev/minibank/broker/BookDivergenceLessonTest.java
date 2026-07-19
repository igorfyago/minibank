package dev.minibank.broker;

import dev.minibank.ledger.Ledger;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * TWO BOOKS, ONE HOLDING · why they diverged and which one was complete.
 *
 * HISTORICAL, AND DELIBERATELY KEPT. The bank used to be able to reach an
 * asset position by TWO paths, and only one of them passed through the
 * broker:
 *
 *   bank tile   POST /api/trade  ->  Products.trade()      ledger only
 *   broker      POST /orders     ->  fill -> Kafka -> Products.settleFill()
 *                                                          broker AND ledger
 *
 * The first one is gone. /api/trade now places a broker order like everything
 * else, and what was Products.trade is now Products.tradeWithoutBroker ·
 * restricted, named for what it does, and used here on purpose.
 *
 * These lessons still earn their place, because they document the MECHANISM
 * of a bug that this bank actually had, and the mechanism is what generalises:
 * tradeWithoutBroker publishes 'trade.executed' to the "payments" topic, whose
 * only subscribers (NotificationsConsumer, ShardApplier) are both inside the
 * ledger. No broker consumer subscribes to it. So the write moved the
 * customer's asset balance and the broker never heard about it, which is the
 * whole divergence in one sentence · and it is the shape any FUTURE escaped
 * write path will take.
 *
 * What CATCHES that escape now lives in SingleWritePathLessonTest, and what
 * repairs the history it left behind lives in Backfill. These four lessons
 * are the diagnosis; those are the cure.
 *
 *   lesson 1  the direct path moves the ledger and NOT the broker
 *   lesson 2  the broker path moves BOTH · it is not the leaky one
 *   lesson 3  the ledger is the SUPERSET · divergence == exactly the direct qty
 *   lesson 4  the price of a direct trade is recoverable from the cash leg,
 *             which is what lets the backfill carry a real cost basis
 *
 * Requires: docker compose up -d
 */
class BookDivergenceLessonTest {

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
        dev.minibank.ledger.Fixtures.resetBrokerDb();
        Catalog.seed();
        VENUE.reset();

        Shards.forCustomer(IGOR).createCustomer(IGOR, "igor");
        Products.ensureFor(IGOR);
        Shards.forCustomer(IGOR).transferLocal(UUID.randomUUID(), Shard.WORLD, IGOR, dec("10000.00"));
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 1: a tile trade moves the LEDGER and the broker never hears about it")
    void lesson1_tileTradeIsInvisibleToTheBroker() throws Exception {
        // 500 EUR of BTC at 50k · the tile path, straight into Products.trade
        Products.tradeWithoutBroker(UUID.randomUUID(), IGOR, "BTC", true, dec("500.00"), dec("50000.00"));

        assertEquals(0, dec("0.01").compareTo(ledgerAsset("BTC")),
                "the ledger credited the bitcoin · 500 at 50k");
        assertEquals(0, BigDecimal.ZERO.compareTo(brokerQty("BTC")),
                "and the broker's position is untouched · nothing routed through it");
        assertEquals(0, fillCount(),
                "no fill exists, because no order was ever placed");

        // the divergence, stated as the reconciliation query would state it
        assertNotEquals(0, ledgerAsset("BTC").compareTo(brokerQty("BTC")),
                "the two books now disagree · this is the bug, reproduced");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 2: the broker path moves BOTH books · it is not the leaky one")
    void lesson2_brokerPathAgrees() throws Exception {
        VENUE.fillAt("50000.00");
        broker.place("b1", IGOR, "BTC", "buy", null, dec("500.00"), "market", null);
        settleAll();

        assertEquals(0, dec("0.01").compareTo(ledgerAsset("BTC")), "ledger credited");
        assertEquals(0, dec("0.01").compareTo(brokerQty("BTC")), "broker credited");
        assertEquals(0, ledgerAsset("BTC").compareTo(brokerQty("BTC")),
                "the books AGREE when the trade goes through the broker");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 3: the ledger is the SUPERSET · divergence is exactly the tile quantity")
    void lesson3_ledgerIsTheSuperset() throws Exception {
        // one of each, same symbol, so the books can be compared directly
        VENUE.fillAt("50000.00");
        broker.place("b1", IGOR, "BTC", "buy", null, dec("500.00"), "market", null);
        settleAll();                                          // broker + ledger: 0.01
        Products.tradeWithoutBroker(UUID.randomUUID(), IGOR, "BTC", true,
                dec("250.00"), dec("50000.00"));              // ledger only:     0.005

        BigDecimal ledger = ledgerAsset("BTC");
        BigDecimal brokerQ = brokerQty("BTC");

        assertEquals(0, dec("0.015").compareTo(ledger), "the ledger saw BOTH paths");
        assertEquals(0, dec("0.01").compareTo(brokerQ), "the broker saw only its own");

        // THE CLAIM, as arithmetic: ledger - broker == the tile quantity, and
        // the sign is never the other way. The broker holds no trade the
        // ledger is missing, so the ledger is the superset and the backfill
        // direction is broker <- ledger, never the reverse.
        assertEquals(0, dec("0.005").compareTo(ledger.subtract(brokerQ)),
                "the gap IS the tile trade · nothing else is unaccounted for");
        assertEquals(1, ledger.compareTo(brokerQ),
                "the ledger is never SHORT of the broker · superset, not merely different");

        // and the ledger's own audits are clean throughout · the divergence
        // is not ledger corruption, it is a book the broker never received
        assertEquals(0, sumZeroViolations(), "every currency still sums to zero");
        assertEquals(0, driftedAccounts(), "and no cached balance drifted");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 4: a tile trade's PRICE is recoverable from its cash leg · the backfill can carry a real cost basis")
    void lesson4_priceIsRecoverable() throws Exception {
        Products.tradeWithoutBroker(UUID.randomUUID(), IGOR, "AAPL", true, dec("1000.00"), dec("200.00"));

        // the same transaction carries both legs: EUR out of the customer,
        // asset into the holding. price = |cash| / |units|, exactly, with no
        // reference to any price feed and no guessing.
        try (Connection c = Shards.forCustomer(IGOR).open();
             var ps = c.prepareStatement("""
                     SELECT SUM(CASE WHEN a.currency = 'EUR'  THEN -e.amount END) AS cash,
                            SUM(CASE WHEN a.currency = 'AAPL' THEN  e.amount END) AS units
                     FROM entries e
                     JOIN accounts a ON a.id = e.account_id
                     JOIN transactions t ON t.id = e.tx_id
                     WHERE t.kind LIKE 'trade:AAPL:%' AND e.account_id IN (?, ?)""")) {
            ps.setLong(1, IGOR);
            ps.setLong(2, IGOR + Products.AAPL);
            try (var rs = ps.executeQuery()) {
                rs.next();
                BigDecimal cash = rs.getBigDecimal("cash");
                BigDecimal units = rs.getBigDecimal("units");
                assertEquals(0, dec("1000.00").compareTo(cash), "the cash leg is in the ledger");
                assertEquals(0, dec("5.00000000").compareTo(units), "so is the asset leg");
                assertEquals(0, dec("200.00").compareTo(
                                cash.divide(units, 8, java.math.RoundingMode.HALF_UP)),
                        "and their ratio is the price the customer actually paid");
            }
        }
    }

    // ------------------------------------------------------------------
    private static BigDecimal dec(String v) { return new BigDecimal(v); }

    /** the ledger's holding, read from the asset account's cached balance */
    private static BigDecimal ledgerAsset(String symbol) throws Exception {
        long acct = IGOR + ("BTC".equals(symbol) ? Products.BTC : Products.AAPL);
        return Shards.forCustomer(IGOR).balance(acct);
    }

    private static BigDecimal brokerQty(String symbol) throws Exception {
        try (Connection c = BrokerDb.open()) {
            return Broker.positionOn(c, IGOR, symbol).qty();
        }
    }

    private static int fillCount() throws Exception {
        try (Connection c = BrokerDb.open();
             var ps = c.prepareStatement("SELECT count(*) FROM fills");
             var rs = ps.executeQuery()) {
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

    private static int sumZeroViolations() throws Exception {
        try (Connection c = Shards.forCustomer(IGOR).open()) {
            return Ledger.sumZeroViolationsOn(c).size();
        }
    }

    private static int driftedAccounts() throws Exception {
        try (Connection c = Shards.forCustomer(IGOR).open()) {
            return Ledger.driftedAccountsOn(c).size();
        }
    }
}
