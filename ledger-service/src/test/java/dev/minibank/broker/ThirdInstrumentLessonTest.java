package dev.minibank.broker;

import dev.minibank.ledger.AssetRegistry;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A THIRD INSTRUMENT, END TO END · the trade the bank could not previously
 * have listed without mis-crediting somebody.
 *
 * The old settlement path did this, and nothing checked it:
 *
 *     String asset = symbol.toLowerCase();                    Settlement
 *     assetAcct = customerId + ("btc".equals(asset)           Products
 *                               ? BTC : AAPL);
 *
 * The HTTP trade endpoint validated the symbol; the KAFKA settlement path
 * did not. So any row the broker's catalog carried could reach the ternary,
 * and everything that was not bitcoin settled into the customer's apple
 * account. The books balanced. The customer's holdings did not.
 *
 *   lesson 1  list a third instrument and buy it · the position lands in ITS
 *             OWN account, and apple is untouched
 *   lesson 2  the whole round trip, through the real saga: broker fills,
 *             Kafka carries, ledger settles, and both books agree
 *   lesson 3  a symbol the BROKER can route but the LEDGER has not listed is
 *             REFUSED, not mis-credited · the asymmetry, closed
 *
 * Requires: docker compose up -d
 */
class ThirdInstrumentLessonTest {

    static final long IGOR = 10;
    /** listed here and not in Catalog.seed() · making a third instrument
     *  possible is the change; choosing what the bank lists is not */
    static final String MSFT = "MSFT";
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
        // ONE call, BOTH halves: routable in the broker, settleable in the
        // ledger. An instrument in only one of them is the old asymmetry.
        Catalog.list(MSFT, "equity", MSFT, "Microsoft", "NASDAQ.NMS");
        VENUE.reset();

        Shards.forCustomer(IGOR).createCustomer(IGOR, "igor");
        Products.ensureFor(IGOR);
        Shards.forCustomer(IGOR).transferLocal(UUID.randomUUID(), Shard.WORLD, IGOR, dec("1000.00"));
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 1: THE BUG · a third instrument settles into its own account, and apple is untouched")
    void lesson1_thirdInstrumentDoesNotLandInApple() throws Exception {
        Shard home = Shards.forCustomer(IGOR);
        long msftAcct = msftAccount();

        VENUE.fillAt("400.00");
        buy("m1", MSFT, "400.00");
        settleAll();

        assertNotEquals(IGOR + Products.AAPL, msftAcct, "this is the account the ternary used to pick");
        assertTrue(msftAcct >= AssetRegistry.ASSET_BASE, "and the new one is nowhere near the legacy range");

        assertEquals(0, dec("1.0").compareTo(home.balance(msftAcct)), "one share of microsoft, in microsoft's account");
        assertEquals(0, BigDecimal.ZERO.compareTo(home.balance(IGOR + Products.AAPL)),
                "APPLE HOLDS NOTHING · the whole point");
        assertEquals(0, BigDecimal.ZERO.compareTo(home.balance(IGOR + Products.BTC)), "and neither does bitcoin");
        assertEquals(0, dec("600.00").compareTo(home.balance(IGOR)), "the cash left once");
        assertEquals(0, dec("400.00").compareTo(home.balance(Shard.BROKER_EUR)), "the broker took the other side");
        assertEquals(0, sumZeroViolations(home), "and every currency sums to zero on its own, MSFT included");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 2: the whole round trip · buy, settle, sell, settle, and both books agree")
    void lesson2_roundTrip() throws Exception {
        Shard home = Shards.forCustomer(IGOR);
        long msftAcct = msftAccount();

        VENUE.fillAt("400.00");
        buy("m1", MSFT, "400.00");
        settleAll();
        drainSettlements();
        assertEquals(0, dec("1.0").compareTo(home.balance(msftAcct)), "long one share");

        VENUE.fillAt("500.00");
        sell("m2", MSFT, "1.0");
        settleAll();
        drainSettlements();

        assertEquals(0, dec("1100.00").compareTo(home.balance(IGOR)), "sold 100 higher");
        assertEquals(0, BigDecimal.ZERO.compareTo(home.balance(msftAcct)), "and holds no microsoft");

        Broker.Position p = position(MSFT);
        assertEquals(0, BigDecimal.ZERO.compareTo(p.qty()), "the broker agrees he is flat");
        assertEquals(0, dec("100.00").compareTo(p.realizedPnl()), "and says he made 100 doing it");
        assertEquals(0, sumZeroViolations(home), "every currency sums to zero");

        // a redelivered fill still moves nothing · the fill id is the txId
        // and that has not changed
        settleAll();
        assertEquals(0, dec("1100.00").compareTo(home.balance(IGOR)), "redelivery is a no-op");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 3: routable but not listed in the LEDGER · refused, not credited to apple")
    void lesson3_brokerOnlyInstrumentIsRefused() throws Exception {
        Shard home = Shards.forCustomer(IGOR);

        // exactly the old hazard: a catalog row with no ledger mapping. Put
        // it in the broker's table DIRECTLY, bypassing Catalog.list, because
        // bypassing the ledger half is the failure being tested.
        try (Connection c = BrokerDb.open();
             var ps = c.prepareStatement("""
                     INSERT INTO instruments(symbol, kind, asset_code, settle_ccy, display_name, exchange)
                     VALUES ('TSLA','equity','TSLA','EUR','Tesla','NASDAQ.NMS')
                     ON CONFLICT (symbol) DO NOTHING""")) {
            ps.executeUpdate();
        }

        VENUE.fillAt("200.00");
        buy("t1", "TSLA", "200.00");
        settleAll();

        assertEquals(0, BigDecimal.ZERO.compareTo(home.balance(IGOR + Products.AAPL)),
                "THE BUG THAT WAS: tesla shares landing in the apple account");
        assertEquals(0, dec("1000.00").compareTo(home.balance(IGOR)), "and not a cent moved");

        Outbox.Event rejected = ledgerOutbox("rejected:");
        assertNotNull(rejected, "the broker is told, durably, so it can unwind");
        assertTrue(rejected.payload().contains("not listed"), "and told why: " + rejected.payload());

        drainSettlements();
        assertEquals(0, BigDecimal.ZERO.compareTo(position("TSLA").qty()),
                "the position is compensated away · the saga's unhappy path, unchanged");

        // told once, however many times Kafka redelivers the fill
        settleAll();
        settleAll();
        assertEquals(1, ledgerOutboxCount("rejected:"), "one refusal, not three");
    }

    // ------------------------------------------------------------------
    private static BigDecimal dec(String v) { return new BigDecimal(v); }

    private static long msftAccount() throws Exception {
        try (Connection c = Shards.forCustomer(IGOR).open()) {
            return AssetRegistry.bySymbol(c, MSFT).holdingFor(IGOR);
        }
    }

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
            for (Outbox.Event e : Outbox.pollUnpublishedOn(c, 50)) {
                if (Settlement.TOPIC_SETTLEMENTS.equals(e.topic()))
                    SettlementConsumer.handle(broker, e.payload());
                Outbox.markPublishedOn(c, e.id(), java.time.Instant.now());
            }
        }
    }

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

    private static int sumZeroViolations(Shard s) throws Exception {
        try (Connection c = s.open()) {
            return Ledger.sumZeroViolationsOn(c).size();
        }
    }
}
