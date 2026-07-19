package dev.minibank.broker;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ONE WATCHLIST, TWO APPS · and the two predicates people keep confusing.
 *
 * The desk kept its rail in localStorage, which meant it was not data: it
 * was a preference that died with a cache clear, on one browser, invisible
 * to the portfolio screen looking at the same customer's book. Moving
 * MEMBERSHIP here is what makes it shared. Order, sections and colour flags
 * stay on the desk, because (customer_id, symbol) cannot hold them and
 * pretending otherwise would mean inventing a CRDT for a feature whose whole
 * value is "the same tickers show up in both apps".
 *
 *   lesson 1  the list belongs to the customer, not to the browser that typed it
 *   lesson 2  WATCHABLE != TRADABLE · SPY can be followed and cannot be bought
 *   lesson 3  a symbol with no mark is unpriced, never zero, and does not
 *             take the rest of the list down with it
 *   lesson 4  adopting a browser's old list is additive and repeatable
 *
 * Lesson 2 is the one that protects an invisible property. watchlist.symbol
 * is bare TEXT while positions.symbol and fills.symbol both REFERENCE
 * instruments, and that asymmetry is deliberate · it is also exactly the kind
 * of thing somebody tidies up. Adding the foreign key, or a Catalog.exists
 * call on the write path, would drop roughly a hundred and eighteen of the
 * desk's hundred and twenty tickers. Nothing failed if you did that before
 * this file existed.
 *
 * Requires: docker compose up -d   (control-plane Postgres on :5433)
 */
class SharedWatchlistLessonTest {

    static final long IGOR = 10;
    /** a symbol no venue has ever listed · the feed answers 404 for it, and
     *  so would it for a typo, which is the point of asserting on the shape
     *  of the answer rather than on a number */
    static final String UNPRICEABLE = "ZZQQNOTAREALSYMBOL";
    static final HttpClient HTTP = HttpClient.newHttpClient();

    static HttpServer server;
    static int port;

    @BeforeAll
    static void boot() throws Exception {
        BrokerDb.createOwnDatabase();
        dev.minibank.ledger.Fixtures.resetBrokerDb();
        Catalog.seed();
        server = new BrokerApi(new Broker(new BrokerLessonTest.StubVenue())).start(0);
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void down() {
        if (server != null) server.stop(0);
    }

    @BeforeEach
    void emptyList() throws Exception {
        try (Connection c = BrokerDb.open(); var st = c.createStatement()) {
            st.execute("SET lock_timeout = '4s'");
            st.execute("TRUNCATE watchlist, account_link");
        }
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 1: the list survives a browser the broker has never seen")
    void lesson1_theListBelongsToTheCustomerNotTheBrowser() throws Exception {
        // browser A claims a book and follows two things
        Accounts.link("browser-A", IGOR);
        post("{\"customer\":" + IGOR + ",\"symbol\":\"NVDA\",\"action\":\"add\"}");
        post("{\"customer\":" + IGOR + ",\"symbol\":\"SPY\",\"action\":\"add\"}");

        // a second browser, a different machine, an empty localStorage. It has
        // never sent this service a single byte before this line.
        Accounts.link("browser-B", IGOR);
        assertEquals(Long.valueOf(IGOR), Accounts.customerFor("browser-B"));

        String body = get("/api/watchlist?customer=" + IGOR);
        assertTrue(body.contains("\"symbol\":\"NVDA\""), "NVDA is on the shared list: " + body);
        assertTrue(body.contains("\"symbol\":\"SPY\""), "so is SPY: " + body);
        assertEquals(List.of("NVDA", "SPY"), Accounts.watchlist(IGOR),
                "and in the order it was built, which is the only order the broker claims to keep");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 2: watchable is not tradable · SPY goes on the list, and cannot be bought")
    void lesson2_watchOnlySymbolIsAcceptedAndLabelled() throws Exception {
        // THE WRITE ACCEPTS IT. No catalog check, no foreign key. If somebody
        // adds either, this line is where it fails.
        post("{\"customer\":" + IGOR + ",\"symbol\":\"SPY\",\"action\":\"add\"}");
        post("{\"customer\":" + IGOR + ",\"symbol\":\"BTC\",\"action\":\"add\"}");
        assertEquals(List.of("SPY", "BTC"), Accounts.watchlist(IGOR),
                "an unlisted symbol is a perfectly good thing to watch");

        // THE READ LABELS IT, so a screen can offer follow on everything and
        // buy only where a buy would fill
        String body = get("/api/watchlist?customer=" + IGOR);
        assertTrue(body.contains("{\"symbol\":\"SPY\"") , "SPY is on the list: " + body);
        assertTrue(row(body, "SPY").contains("\"tradable\":false"),
                "and it is marked watch-only: " + row(body, "SPY"));
        assertTrue(row(body, "BTC").contains("\"tradable\":true"),
                "while a listed instrument is not: " + row(body, "BTC"));

        // and the gate that matters is still the order path, untouched
        String refused = post("/api/orders",
                "{\"clientOrderId\":\"spy-" + System.nanoTime() + "\",\"customer\":" + IGOR
                + ",\"symbol\":\"SPY\",\"side\":\"buy\",\"notional\":\"50.00\",\"type\":\"market\"}");
        assertTrue(refused.contains("not a listed instrument"),
                "watching SPY never made SPY routable: " + refused);
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 3: no mark is a gap, not a zero · and one gap does not empty the list")
    void lesson3_unpriceableSymbolIsUnpricedNotZero() throws Exception {
        post("{\"customer\":" + IGOR + ",\"symbol\":\"" + UNPRICEABLE + "\",\"action\":\"add\"}");
        post("{\"customer\":" + IGOR + ",\"symbol\":\"BTC\",\"action\":\"add\"}");

        String body = get("/api/watchlist?customer=" + IGOR);
        String dead = row(body, UNPRICEABLE);

        assertTrue(dead.contains("\"price\":null"),
                "a price we do not have is a bare null: " + dead);
        assertFalse(dead.contains("\"price\":\"0"),
                "zero is a price, and it is the wrong one · it reads as worthless: " + dead);

        // the row is still THERE, and so is its neighbour. An unpriceable
        // symbol used to be able to take a whole panel down with it.
        assertEquals(2, count(body, "\"symbol\":\""),
                "both rows rendered · one missing mark is not an outage: " + body);
        assertTrue(body.contains("\"symbol\":\"BTC\""), "BTC survived its neighbour: " + body);
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 4: adopting a browser's old list is additive, and running it twice changes nothing")
    void lesson4_importIsAdditiveAndIdempotent() throws Exception {
        // something is already on the shared list · another browser put it
        // there, and a migration must not be allowed to delete it
        post("{\"customer\":" + IGOR + ",\"symbol\":\"BTC\",\"action\":\"add\"}");

        String batch = "{\"customer\":" + IGOR + ",\"action\":\"import\",\"symbols\":["
                + "{\"symbol\":\"spy\"},{\"symbol\":\"QQQ\"},{\"symbol\":\"BTC\"}]}";

        String first = post(batch);
        assertTrue(first.contains("\"imported\":3"), "all three were offered: " + first);
        assertEquals(List.of("BTC", "SPY", "QQQ"), Accounts.watchlist(IGOR),
                "BTC kept its place, the new ones landed normalised and in order");

        // FIRST LOAD RAN IT. A refresh, a second tab, or a retry after a
        // timeout runs it again, and the list must be the same list.
        post(batch);
        assertEquals(List.of("BTC", "SPY", "QQQ"), Accounts.watchlist(IGOR),
                "re-importing is a no-op · no duplicates, no reordering, no resurrection");
    }

    // ------------------------------------------------------------------
    /**
     * The backend labelling a row watch-only is worth nothing if the screen
     * still draws a buy button on it · the customer taps it, the order path
     * refuses, and the page explains a failure it could have prevented.
     *
     * STRUCTURAL, for the same reason lesson 3 of PortfolioDayHonestyLessonTest
     * is: this page fetches over HTTP and a unit test cannot render it. What
     * can be proved is the thing that was missing · the field being read at
     * all, inside the function that draws the row.
     */
    @Test
    @DisplayName("lesson 5: the watchlist card reads `tradable` · a watch-only row does not offer a buy")
    void lesson5_theScreenDrawsWatchOnlyInsteadOfABuyButton() throws Exception {
        String page = resource("/web-broker/portfolio.html");
        int at = page.indexOf("function drawWatchlist");
        assertTrue(at >= 0, "drawWatchlist still exists");
        int next = page.indexOf("\nwindow.", at + 1);
        String draw = page.substring(at, next < 0 ? page.length() : next);

        // \b so w.tradableSomething cannot pass for w.tradable · a substring
        // match would let a rename keep this green
        assertTrue(java.util.regex.Pattern.compile("\\bw\\.tradable\\b").matcher(draw).find(),
                "BrokerApi ships \"tradable\":true|false per row and the CARD must read it:\n" + draw);
        assertTrue(draw.contains("watch only"),
                "and it must say so in words, not merely branch on it:\n" + draw);

        // the half that actually protects the customer: the buy button is
        // GUARDED by the test, not merely drawn near it. Written as an
        // ordering because that survives the guard being written either way
        // round · `tradable === false ? chip : buy` and `tradable !== false ?
        // buy : chip` both put the field first, and deleting the guard is the
        // only edit that puts placeOrder first.
        int guard = draw.indexOf("w.tradable");
        int buy = draw.indexOf("placeOrder");
        assertTrue(buy > guard,
                "the buy button must sit inside the tradable test, not before it:\n" + draw);
    }

    // ------------------------------------------------------------------
    private static String resource(String path) throws Exception {
        try (java.io.InputStream in = SharedWatchlistLessonTest.class.getResourceAsStream(path)) {
            assertTrue(in != null, "missing resource " + path);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------
    /** the one JSON object in `body` for this symbol · these responses are
     *  built by hand, so a substring is honest here where it would not be
     *  against a real serializer */
    private static String row(String body, String symbol) {
        int i = body.indexOf("{\"symbol\":\"" + symbol + "\"");
        if (i < 0) return "";
        int end = body.indexOf('}', i);
        return end < 0 ? body.substring(i) : body.substring(i, end + 1);
    }

    private static int count(String body, String needle) {
        int n = 0, from = 0;
        while (true) {
            int i = body.indexOf(needle, from);
            if (i < 0) return n;
            n++;
            from = i + needle.length();
        }
    }

    private static String get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private static String post(String body) throws Exception {
        return post("/api/watchlist", body);
    }

    private static String post(String path, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }
}
