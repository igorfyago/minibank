package dev.minibank.broker;

import com.sun.net.httpserver.HttpServer;
import dev.minibank.ledger.HttpApi;
import dev.minibank.ledger.Products;
import dev.minibank.ledger.Settlement;
import dev.minibank.ledger.Shard;
import dev.minibank.ledger.Shards;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE BANK'S TILE AND THE PORTFOLIO SCREEN ARE THE SAME SERVICE.
 *
 * Not "similar", not "kept in sync" · the same order table, the same fills,
 * the same settlement saga. The bank's quick-buy used to be a completely
 * separate mechanism that happened to produce a similar-looking balance, and
 * the two only agreed for as long as nobody checked.
 *
 * WHY THE LEDGER CALLS THE BROKER RATHER THAN THE BROWSER DOING IT. The bank
 * page is served from the ledger's port and the broker listens on another
 * one, so a browser calling the broker directly is cross-origin and needs
 * CORS on the broker · the service holding the order book and the cost
 * basis. BrokerApi already declined to open that door once, on purpose, for
 * the price-history route. Routing through the ledger means the browser only
 * ever talks to the origin it was served from, so no CORS is needed anywhere,
 * and the ledger already makes exactly this kind of outbound call for FX and
 * prices. A reverse proxy would work too and needs deployment config that
 * local development does not have; this works identically in both.
 *
 *   lesson 1  POST /api/trade creates a BROKER ORDER · the whole point
 *   lesson 2  ... and writes nothing to the ledger until the fill settles
 *   lesson 3  the endpoint's answer is honest about that
 *   lesson 4  the txId stays the idempotency key · a retried click is one order
 *   lesson 5  no broker, no trade · and no quiet fallback to the old path
 *
 * Requires: docker compose up -d
 */
class BankTileRoutesToBrokerLessonTest {

    static final long IGOR = 10;
    static final BrokerLessonTest.StubVenue VENUE = new BrokerLessonTest.StubVenue();
    static Broker broker;
    static HttpServer bank, brokerServer;
    static int bankPort;
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    static void boot() throws Exception {
        Shards.boot(
                System.getenv().getOrDefault("MINIBANK_SHARD0_URL", "jdbc:postgresql://localhost:5434/minibank"),
                System.getenv().getOrDefault("MINIBANK_SHARD1_URL", "jdbc:postgresql://localhost:5435/minibank"),
                "minibank", "minibank", 5);
        for (Shard s : Shards.all()) s.createSchema();
        Shards.nameRegions("eu", "uk");
        BrokerDb.createOwnDatabase();
        Catalog.seed();

        broker = new Broker(VENUE);
        brokerServer = new BrokerApi(broker).start(0);
        // the ledger's view of where the broker lives · a real HTTP hop to a
        // real broker on a real port, because a mocked one would prove only
        // that a mock was called
        System.setProperty("broker.url", "http://localhost:" + brokerServer.getAddress().getPort());

        bank = HttpApi.start(0);
        bankPort = bank.getAddress().getPort();
    }

    @AfterAll
    static void down() {
        if (bank != null) bank.stop(0);
        if (brokerServer != null) brokerServer.stop(0);
        System.clearProperty("broker.url");
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
        VENUE.fillAt("50000.00");

        Shards.forCustomer(IGOR).createCustomer(IGOR, "igor");
        Products.ensureFor(IGOR);
        Shards.forCustomer(IGOR).transferLocal(UUID.randomUUID(), Shard.WORLD, IGOR, dec("10000.00"));
        System.setProperty("broker.url", "http://localhost:" + brokerServer.getAddress().getPort());
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 1: the bank's quick-buy places a BROKER ORDER · one pipeline, not two mechanisms")
    void lesson1_tileCreatesABrokerOrder() throws Exception {
        String txId = UUID.randomUUID().toString();
        String body = trade(txId, "btc", "buy", "500.00");
        assertTrue(body.contains("\"result\":\"placed\""), body);

        try (Connection c = BrokerDb.open()) {
            Broker.Order o = Broker.byClientId(c, txId);
            assertNotNull(o, "the click became an order in the broker's own table");
            assertEquals(IGOR, o.customerId());
            assertEquals("BTC", o.symbol());
            assertEquals("buy", o.side());
            assertEquals(0, dec("500.00").compareTo(o.notional()),
                    "sized in EUR, exactly as the tile asked");
            assertEquals("filled", o.status(), "and the venue filled it");
        }
        assertEquals(1, fillCount(), "one fill · the same kind the portfolio screen produces");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 2: the ledger writes NOTHING until the fill settles · it no longer originates asset moves")
    void lesson2_ledgerWaitsForSettlement() throws Exception {
        BigDecimal cashBefore = Shards.forCustomer(IGOR).balance(IGOR);
        trade(UUID.randomUUID().toString(), "btc", "buy", "500.00");

        assertEquals(0, cashBefore.compareTo(Shards.forCustomer(IGOR).balance(IGOR)),
                "the money has not moved · the order is filled, not settled");
        assertEquals(0, BigDecimal.ZERO.compareTo(
                        Shards.forCustomer(IGOR).balance(IGOR + Products.BTC)),
                "and no bitcoin has been credited");
        assertEquals(0, tradeKindCount(),
                "and no 'trade:' transaction exists · THE retired write path, proven gone");

        // ... and the broker being ahead here is the legitimate in-flight
        // window, not a break
        assertEquals(List.of(), Reconciliation.divergences());

        settleAll();
        assertEquals(0, dec("0.01").compareTo(Shards.forCustomer(IGOR).balance(IGOR + Products.BTC)),
                "the settlement is what credits the holding · 500 at 50k");
        assertEquals(1, settleKindCount(), "recorded as a settlement, which is what it is");
        assertEquals(List.of(), Reconciliation.divergences(), "and the books agree");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 3: the answer is honest · an order was placed, nothing was bought yet")
    void lesson3_theAnswerDoesNotOverclaim() throws Exception {
        String body = trade(UUID.randomUUID().toString(), "btc", "buy", "500.00");

        assertTrue(body.contains("\"settlement\":\"asynchronous\""),
                "the response says the money moves later · " + body);
        assertTrue(body.contains("\"orderId\""), "and names the order the customer can be shown");
        // the old contract claimed the trade was done by the time the button
        // released. Anything still asserting result:"ok" was asserting a lie.
        assertTrue(!body.contains("\"result\":\"ok\""),
                "'ok' meant 'your balance changed', and it has not · " + body);
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 4: the txId is still the idempotency key · it just gates an order now")
    void lesson4_retriedClickIsOneOrder() throws Exception {
        String txId = UUID.randomUUID().toString();
        trade(txId, "btc", "buy", "500.00");
        trade(txId, "btc", "buy", "500.00");
        trade(txId, "btc", "buy", "500.00");

        assertEquals(1, orderCount(), "three clicks, one order · the broker's UNIQUE index is the gate");
        assertEquals(1, fillCount(), "and one fill");

        settleAll();
        assertEquals(0, dec("0.01").compareTo(Shards.forCustomer(IGOR).balance(IGOR + Products.BTC)),
                "charged once, delivered once");
    }

    // ------------------------------------------------------------------
    /**
     * The failure that must stay loud. FxClient falls back to the last good
     * rate when the FX service is down, because a slightly stale rate beats a
     * stalled payment. There is no such trade for an order: the only other
     * way to acquire the asset is the write path this change deleted, so
     * "the broker did not answer" has to mean the trade did not happen.
     */
    @Test
    @DisplayName("lesson 5: no broker, no trade · the refusal is loud and there is no quiet fallback")
    void lesson5_noBrokerNoTrade() throws Exception {
        System.setProperty("broker.url", "http://localhost:1");   // nothing listens here

        HttpResponse<String> r = tradeRaw(UUID.randomUUID().toString(), "btc", "buy", "500.00");
        assertEquals(503, r.statusCode(), "unavailable, not 'ok' · " + r.body());
        assertTrue(r.body().contains("was not placed"), r.body());

        assertEquals(0, tradeKindCount(),
                "and CRUCIALLY it did not fall back to writing the trade into the ledger");
        assertEquals(0, orderCount());
    }

    // ------------------------------------------------------------------
    private static BigDecimal dec(String v) { return new BigDecimal(v); }

    private static String trade(String txId, String asset, String side, String eur) throws Exception {
        HttpResponse<String> r = tradeRaw(txId, asset, side, eur);
        assertEquals(200, r.statusCode(), r.body());
        return r.body();
    }

    private static HttpResponse<String> tradeRaw(String txId, String asset, String side, String eur)
            throws Exception {
        String body = "{\"txId\":\"" + txId + "\",\"customer\":" + IGOR
                + ",\"asset\":\"" + asset + "\",\"side\":\"" + side + "\",\"eur\":\"" + eur + "\"}";
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + bankPort + "/api/trade"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static int orderCount() throws Exception {
        return count(BrokerDb.open(), "SELECT count(*) FROM orders");
    }

    private static int fillCount() throws Exception {
        return count(BrokerDb.open(), "SELECT count(*) FROM fills");
    }

    private static int tradeKindCount() throws Exception {
        return count(Shards.forCustomer(IGOR).open(),
                "SELECT count(*) FROM transactions WHERE kind LIKE 'trade:%'");
    }

    private static int settleKindCount() throws Exception {
        return count(Shards.forCustomer(IGOR).open(),
                "SELECT count(*) FROM transactions WHERE kind LIKE 'settle:%'");
    }

    private static int count(Connection open, String sql) throws Exception {
        try (Connection c = open; var ps = c.prepareStatement(sql); var rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
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
}
