package dev.minibank.broker;

import com.sun.net.httpserver.HttpServer;
import dev.minibank.ledger.HttpApi;
import dev.minibank.ledger.Products;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CLOSING A POSITION NAMES THE UNITS · a euro amount cannot say "all of it".
 *
 * "Sell all" looks like it should be a euro order for the position's current
 * value, and that is what the bank's tile used to send. It does not work, and
 * the way it fails is worth keeping written down because it looks like a
 * rounding nit and is not.
 *
 * The screen reads a value, the customer clicks, the venue divides that value
 * by the price AT FILL TIME. Between those two moments the price moved. If it
 * moved DOWN, the same euros now buy more units than the customer owns, the
 * broker refuses the whole order ("cannot sell 0.00088516 BTC: position is
 * 0.00088427") and the position is stuck · not by a little, but completely,
 * and only for the person trying to get out. Observed in a browser against a
 * live feed on the first attempt, not theorised.
 *
 * The quantity, meanwhile, is a number both sides already agree on exactly.
 * So the endpoint takes units, and 'sell all' sends the position.
 *
 *   lesson 1  /api/trade accepts units and sizes the order by QUANTITY
 *   lesson 2  the units are passed through EXACTLY · not re-derived
 *   lesson 3  eur and units are alternatives · asking for both is refused
 *   lesson 4  a quantity order needs no price · it closes a position blind
 *
 * Requires: docker compose up -d
 */
class CloseAPositionLessonTest {

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
    @DisplayName("lesson 1: /api/trade sizes an order by UNITS when it is given units")
    void lesson1_unitsSizeTheOrder() throws Exception {
        String txId = UUID.randomUUID().toString();
        HttpResponse<String> r = tradeUnits(txId, "btc", "buy", "0.00250000");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"result\":\"placed\""), r.body());

        try (Connection c = BrokerDb.open()) {
            Broker.Order o = Broker.byClientId(c, txId);
            assertNotNull(o, "the click became an order");
            assertEquals(0, dec("0.00250000").compareTo(o.qty()),
                    "sized in UNITS · this is the whole point of the field");
            assertNull(o.notional(),
                    "and NOT in euros · a quantity order that also carries a notional is two "
                            + "instructions and the venue would have to pick one");
        }
    }

    @Test
    @DisplayName("lesson 2: the units reach the venue exactly · never re-derived through a price")
    void lesson2_unitsArePassedThroughExactly() throws Exception {
        // eight decimals, deliberately · this is the precision a euro amount
        // divided by a moving price cannot round-trip
        String exact = "0.00088427";
        String txId = UUID.randomUUID().toString();
        assertEquals(200, tradeUnits(txId, "btc", "buy", exact).statusCode());

        try (Connection c = BrokerDb.open()) {
            Broker.Order o = Broker.byClientId(c, txId);
            assertEquals(0, dec(exact).compareTo(o.qty()),
                    "the eighth decimal survives · it is the difference between closing a "
                            + "position and being refused for overshooting it");
        }
    }

    @Test
    @DisplayName("lesson 3: eur and units are alternatives · sending both is refused, not guessed at")
    void lesson3_bothSizesIsARefusal() throws Exception {
        String body = "{\"txId\":\"" + UUID.randomUUID() + "\",\"customer\":" + IGOR
                + ",\"asset\":\"btc\",\"side\":\"buy\",\"eur\":\"100.00\",\"units\":\"0.001\"}";
        HttpResponse<String> r = post(body);
        assertEquals(400, r.statusCode(), r.body());
        // picking one silently would mean the customer's order is a coin flip
        // between two sizes they can both see on their own screen
        assertTrue(r.body().contains("not both"), r.body());
    }

    @Test
    @DisplayName("lesson 4: a quantity order needs no price · closing out works with the feed down")
    void lesson4_noPriceNeededToClose() throws Exception {
        // Buy in, then close by naming the units. Nothing in this path asks
        // PriceFeed anything · which is why 'sell all' stays available on the
        // tile even when the price is unavailable, while 'sell €50' cannot be.
        assertEquals(200, tradeUnits(UUID.randomUUID().toString(), "btc", "buy", "0.00400000").statusCode());
        try (Connection c = BrokerDb.open()) {
            assertEquals(0, dec("0.00400000").compareTo(Broker.positionOn(c, IGOR, "BTC").qty()));
        }

        String txId = UUID.randomUUID().toString();
        HttpResponse<String> r = tradeUnits(txId, "btc", "sell", "0.00400000");
        assertEquals(200, r.statusCode(), r.body());

        try (Connection c = BrokerDb.open()) {
            Broker.Order o = Broker.byClientId(c, txId);
            assertEquals("sell", o.side());
            assertEquals("filled", o.status(),
                    "selling EXACTLY the position is never an overshoot · that is the fix");
            assertEquals(0, BigDecimal.ZERO.compareTo(Broker.positionOn(c, IGOR, "BTC").qty()),
                    "and the position is flat");
        }
    }

    // ------------------------------------------------------------------
    private static HttpResponse<String> tradeUnits(String txId, String asset, String side, String units)
            throws Exception {
        return post("{\"txId\":\"" + txId + "\",\"customer\":" + IGOR
                + ",\"asset\":\"" + asset + "\",\"side\":\"" + side + "\",\"units\":\"" + units + "\"}");
    }

    private static HttpResponse<String> post(String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + bankPort + "/api/trade"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static BigDecimal dec(String s) {
        return new BigDecimal(s);
    }
}
