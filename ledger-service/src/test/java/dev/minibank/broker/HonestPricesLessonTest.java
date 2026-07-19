package dev.minibank.broker;

import com.sun.net.httpserver.HttpServer;
import dev.minibank.ledger.AssetRegistry;
import dev.minibank.ledger.HttpApi;
import dev.minibank.ledger.PriceFeed;
import dev.minibank.ledger.Products;
import dev.minibank.ledger.Shard;
import dev.minibank.ledger.Shards;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A PRICE IS AN OBSERVATION OR IT IS NOTHING.
 *
 * PriceFeed used to answer "the upstream is down" with a hardcoded 90,000 for
 * bitcoin and 195 for Apple, tagged 'fallback' so a screen could grey it out.
 * The label lost to the number every time: a figure rendered at the size of a
 * balance is read as a balance, whatever word sits beside it.
 *
 * The deeper reason it had to go is that the number was never true at ANY
 * moment. A stale price is a real observation with an old timestamp; an
 * invented one is a fact about nothing. That is why the 'cached' path (serve
 * the last price we really saw) survives and the fabrication does not.
 *
 * Downstream, "I do not know" has to survive contact with arithmetic · which
 * is the second half of this file. A total that quietly sums the legs it
 * could price renders identically to a complete one, and the reader cannot
 * tell which they are looking at. So the totals are WITHHELD instead.
 *
 *   lesson 1  there is no table of invented prices left in PriceFeed
 *   lesson 2  a symbol nothing can price comes back unpriced, not zero
 *   lesson 3  the bank's shelf withholds the total rather than part-summing it
 *   lesson 4  ... and the holding is still listed, because unpriced is not gone
 *   lesson 5  the shelf reads the REGISTRY, not a two-symbol literal
 *
 * Requires: docker compose up -d
 */
class HonestPricesLessonTest {

    static final long IGOR = 10;
    /** A symbol no feed on earth answers for · the point is the absence. */
    static final String GHOST = "ZZQQ";

    static HttpServer bank;
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
        bank = HttpApi.start(0);
        bankPort = bank.getAddress().getPort();
    }

    @AfterAll
    static void down() {
        if (bank != null) bank.stop(0);
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
        Shards.forCustomer(IGOR).createCustomer(IGOR, "igor");
        Products.ensureFor(IGOR);
        Shards.forCustomer(IGOR).transferLocal(UUID.randomUUID(), Shard.WORLD, IGOR, dec("10000.00"));
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 1: PriceFeed carries no table of invented prices")
    void lesson1_noFabricatedPriceTable() {
        // A STRUCTURAL ASSERTION, deliberately, and worth saying why. The
        // behavioural difference between "invent 90,000" and "admit nothing"
        // only shows when the upstream is DOWN, and this class talks to
        // CoinGecko and Yahoo over hardcoded URLs that a test cannot cut. So
        // the thing that can actually be checked here is the thing that was
        // actually deleted: a static map of prices that no one observed.
        //
        // Restore FALLBACK/FALLBACK_USD and this fails.
        for (Field f : PriceFeed.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            if (!Map.class.isAssignableFrom(f.getType())) continue;
            f.setAccessible(true);
            Object v;
            try {
                v = f.get(null);
            } catch (IllegalAccessException e) {
                continue;
            }
            if (!(v instanceof Map<?, ?> m)) continue;
            for (Object val : m.values()) {
                assertFalse(val instanceof BigDecimal,
                        "PriceFeed." + f.getName() + " holds a hardcoded price (" + val
                                + ") · a price nobody observed is not a price");
            }
        }
    }

    @Test
    @DisplayName("lesson 2: a symbol nothing can price comes back unpriced · never a zero")
    void lesson2_unknownSymbolIsUnpriced() {
        PriceFeed.Px px = PriceFeed.get(GHOST.toLowerCase());
        assertNotNull(px, "the feed answers · it just has nothing to say");
        assertFalse(px.priced(), "an unknown symbol must not acquire a price");
        // and specifically NOT zero · a zero would value the holding at
        // nothing and report a 100% loss on a position that is perfectly fine
        assertTrue(px.price() == null || px.price().signum() == 0 ? px.price() == null : false,
                "unpriced means null, not 0.00 · a zero is a number and reads as one");
    }

    @Test
    @DisplayName("lesson 3: one unpriced holding withholds the total · it is not part-summed")
    void lesson3_totalIsWithheldNotPartial() throws Exception {
        giveHolding(GHOST, "3.00000000");

        String body = portfolio();
        assertTrue(body.contains("\"unpriced\":1"), body);
        assertTrue(body.contains("\"value\":null"),
                "a total that silently drops the leg it could not price looks exactly like a "
                        + "complete one · so there is no total: " + body);
        assertTrue(body.contains("\"dayChange\":null"),
                "and no day change either, for the same reason: " + body);
    }

    @Test
    @DisplayName("lesson 4: the unpriced holding is still LISTED · unknown is not gone")
    void lesson4_unpricedHoldingStillAppears() throws Exception {
        giveHolding(GHOST, "3.00000000");

        String body = portfolio();
        assertTrue(body.contains("\"asset\":\"" + GHOST + "\""),
                "withholding the TOTAL must not disappear the position: " + body);
        assertTrue(body.contains("\"units\":\"3\""), body);
        assertTrue(body.contains("\"eur\":null"),
                "its value is unknown, and says so, rather than being valued at zero: " + body);
        assertTrue(body.contains("\"priceSource\":\"unavailable\""), body);
    }

    @Test
    @DisplayName("lesson 5: the shelf lists whatever the REGISTRY lists · not a hardcoded pair")
    void lesson5_shelfFollowsTheRegistry() throws Exception {
        // This is the ternary the ledger and the statement each had removed,
        // one layer up: the bank's product shelf named btc and aapl in its own
        // source, so an instrument registered afterwards gave the customer a
        // holding with nowhere to appear.
        giveHolding(GHOST, "1.50000000");

        String body = portfolio();
        assertTrue(body.contains("\"asset\":\"" + GHOST + "\""),
                GHOST + " is registered and held, so it belongs on the shelf · a shelf that "
                        + "only knows btc and aapl cannot show it: " + body);
        assertTrue(body.contains("\"holdings\":1"), body);
    }

    // ------------------------------------------------------------------
    /** Register an instrument and put units of it in the customer's hands,
     *  through the settlement path rather than by writing a balance. */
    private static void giveHolding(String symbol, String units) throws Exception {
        AssetRegistry.register(symbol, symbol);
        Products.settleFill(UUID.randomUUID(), IGOR, symbol, true, dec(units), dec("100.00"));
    }

    private static String portfolio() throws Exception {
        HttpResponse<String> r = HTTP.send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + bankPort + "/api/portfolio?customer=" + IGOR)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return r.body();
    }

    private static BigDecimal dec(String s) {
        return new BigDecimal(s);
    }
}
