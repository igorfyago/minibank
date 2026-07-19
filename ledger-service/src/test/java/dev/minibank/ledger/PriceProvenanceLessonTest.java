package dev.minibank.ledger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WHERE THE NUMBER CAME FROM · the half of the fabrication that survived.
 *
 * PriceFeed's docstring says every price it returns was observed, and for the
 * PRICE leg that became true: the hardcoded 90,000 for bitcoin and 195 for
 * Apple are gone, and a symbol nothing can price comes back unpriced. The
 * CURRENCY leg was never audited. An equity mark is a Yahoo USD price times a
 * rate from the FX service, and when that service is unreachable with nothing
 * cached, FxClient answers with a hardcoded 0.88. fetch() took the rate,
 * discarded the source, and labelled the product 'live'.
 *
 * That is the same bug the class was cleaned of, one field over, and it was
 * worse in one specific way: nothing downstream could badge it, because
 * nothing downstream was told.
 *
 *   lesson 1  a rate nobody quoted is not an observation, and says so
 *   lesson 2  no observed rate means NO euro mark · not an invented one
 *   lesson 3  an old rate survives, and it makes the whole mark 'cached'
 *   lesson 4  the bank tile names the fx-service only when it answered
 *   lesson 5  a missing price does not take the support agent to a 500
 *   lesson 6  the chart endpoint accepts the symbol the UI actually sends
 *
 * Requires (lesson 6 only): docker compose up -d
 */
class PriceProvenanceLessonTest {

    static HttpServer bank;
    static int port;
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
        port = bank.getAddress().getPort();
    }

    @AfterAll
    static void down() {
        if (bank != null) bank.stop(0);
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 1: a rate nobody ever quoted is not an observation, and the rate itself says so")
    void lesson1_theFallbackAdmitsWhatItIs() throws Exception {
        // FxClient remembers the last rate it was really given, in a static
        // that anything running earlier may have warmed · including the live
        // fx-service on this machine. "Nobody ever quoted a rate" is the case
        // under test, so it has to be arranged rather than hoped for.
        java.lang.reflect.Field lastGood = FxClient.class.getDeclaredField("lastGood");
        lastGood.setAccessible(true);
        Object remembered = lastGood.get(null);
        try {
            lastGood.set(null, null);
            FxClient.Rate down = FxClient.from("http://localhost:1");   // nothing listens
            assertEquals(FxClient.FALLBACK_SOURCE, down.source(), "it is honest in the source string");
            assertEquals(0, new BigDecimal("0.88").compareTo(down.rate()), "and it is the constant");
            assertTrue(!down.observed(),
                    "a constant somebody typed is a fact about nothing · callers that PUBLISH "
                    + "a figure have to be able to ask this, and until now they could not");
            // the money path may still USE it to keep moving · that is what a
            // fallback is for, and this lesson does not take it away
            assertNotNull(down.rate());
        } finally {
            lastGood.set(null, remembered);
        }

        // a rate that WAS quoted, however long ago, is an observation · the
        // distinction this whole lesson turns on is old versus invented
        assertTrue(new FxClient.Rate(new BigDecimal("0.9"), "fx down · last good").observed(),
                "old is not the same as made up");
        assertTrue(new FxClient.Rate(new BigDecimal("0.9"), "live").observed());
    }

    // ------------------------------------------------------------------
    /**
     * The conversion, extracted so it can be asked the question the live
     * fetch cannot be asked: what do you do when the rate is invented?
     */
    @Test
    @DisplayName("lesson 2: no observed rate means NO euro mark · never a euro mark built on a constant")
    void lesson2_noInventedEuroPrice() {
        BigDecimal usd = new BigDecimal("195.00");
        FxClient.Rate invented = new FxClient.Rate(new BigDecimal("0.88"), FxClient.FALLBACK_SOURCE);

        assertThrows(IllegalStateException.class,
                () -> PriceFeed.toEur(usd, new BigDecimal("190.00"), invented),
                "a real USD price times an invented rate is an invented EUR price · "
                + "there is no honest mark here, so there is no mark");

        // and specifically NOT 195 * 0.88 = 171.60 quietly labelled live
        assertThrows(IllegalStateException.class, () -> PriceFeed.toEur(usd, null, invented));
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 3: a rate that is merely OLD survives · and it makes the whole mark 'cached'")
    void lesson3_staleRateDowngradesTheLabel() {
        BigDecimal usd = new BigDecimal("200.00");
        BigDecimal rate = new BigDecimal("0.90");

        PriceFeed.Px live = PriceFeed.toEur(usd, new BigDecimal("100.00"),
                new FxClient.Rate(rate, "live"));
        assertEquals("live", live.source(), "both legs current");
        assertEquals(0, new BigDecimal("180.00").compareTo(live.price()));
        assertEquals(0, new BigDecimal("90.00").compareTo(live.prevClose()),
                "and BOTH legs use the same rate · converting them on different rates "
                + "would smuggle FX drift into the stock's day move");

        PriceFeed.Px old = PriceFeed.toEur(usd, null, new FxClient.Rate(rate, "fx down · last good"));
        assertTrue(old.priced(), "the rate was really quoted once, so the mark is real");
        assertNotEquals("live", old.source(),
                "but a fresh USD price at yesterday's rate is not a live EUR price · "
                + "the label follows the WEAKER leg or it overstates");
        assertEquals("cached", old.source(), "which is the word this codebase already uses for that");
    }

    // ------------------------------------------------------------------
    /**
     * HttpApi ships fxSource alongside fxRate precisely so the screen can tell
     * the two apart, and the tile read it nowhere · printing "via fx-service"
     * unconditionally, including when the fx-service was down and the number
     * was FxClient's constant. Naming a service that did not supply a figure
     * is not a rounding error in the copy, it is an attribution that is false.
     */
    @Test
    @DisplayName("lesson 4: the bank tile names the fx-service only when the fx-service answered")
    void lesson4_theTileAttributesTheRateHonestly() throws Exception {
        String page = resource("/web/index.html");
        assertTrue(page.contains("fxSource"),
                "the field exists in the payload for exactly this and must be read");
        int tile = page.indexOf("$→€ ");
        assertTrue(tile >= 0, "the rate is still printed on the wealth tile");
        String around = page.substring(tile, Math.min(page.length(), tile + 200));
        assertTrue(!around.contains("via fx-service") || around.contains("fxWho"),
                "it must not state 'via fx-service' unconditionally · that line read "
                + "\"$→€ 0.8800 via fx-service\" with the service unreachable");
    }

    // ------------------------------------------------------------------
    /**
     * Deleting the fabrication made PriceFeed.get().price() genuinely
     * nullable. HttpApi was updated for that; SupportAgent was not, and it
     * dereferences the price for btc and aapl while building Rita's system
     * prompt · the two symbols the deleted constants used to cover, so the
     * NPE was introduced by the very change that made the feed honest.
     */
    @Test
    @DisplayName("lesson 5: a price the feed does not have is a word, not a NullPointerException")
    void lesson5_missingPriceDoesNotBreakTheAgent() {
        PriceFeed.Px nothing = new PriceFeed.Px(null, null, "unavailable", null);
        assertEquals("unavailable", SupportAgent.px(nothing),
                "the prompt says so plainly · the model is told never to invent data, "
                + "and a price it does not have is exactly what it must not fill in");
        assertEquals("unavailable", SupportAgent.px(null), "and a missing Px is not a crash either");

        PriceFeed.Px real = new PriceFeed.Px(new BigDecimal("195.00"), new BigDecimal("195.00"), "live", null);
        assertEquals("195.00", SupportAgent.px(real), "a real price is still just the number");
    }

    // ------------------------------------------------------------------
    /**
     * The bank's Investments tile calls this with the REGISTRY symbol, which
     * is uppercase. The endpoint compared against the literals "btc" and
     * "aapl", so every chart request came back 400 and the sparkline never
     * rendered · silently, because the browser's catch swallows it and leaves
     * blank space rather than an error.
     *
     * portfolio() documents this exact trap and fixes it with toLowerCase
     * before touching PriceFeed; BrokerApi's own copy of this route does the
     * same. This one did not.
     */
    @Test
    @DisplayName("lesson 6: the chart endpoint accepts the symbol the UI actually sends · uppercase")
    void lesson6_historyAcceptsTheRegistrySymbol() throws Exception {
        HttpResponse<String> upper = get("/api/prices/history?asset=BTC");
        assertEquals(200, upper.statusCode(),
                "index.html passes the registry symbol verbatim and it is UPPERCASE · "
                + "rejecting it is how the tile's sparkline came to be permanently blank");
        assertTrue(upper.body().contains("\"points\""), "and it answers with a series: " + upper.body());

        // the same instrument, spelled the other way, is the same instrument
        assertEquals(200, get("/api/prices/history?asset=btc").statusCode());
        assertEquals(200, get("/api/prices/history?asset=AAPL").statusCode());

        // and an instrument this bank does not list is still refused · the
        // fix is case folding, not opening the route up
        assertEquals(400, get("/api/prices/history?asset=NOTLISTED").statusCode(),
                "an unlisted symbol must not become an open proxy to Yahoo with our IP on it");
        assertEquals(400, get("/api/prices/history").statusCode(), "and no symbol is still a 400");
    }

    // ------------------------------------------------------------------
    private static HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String resource(String path) throws Exception {
        try (InputStream in = PriceProvenanceLessonTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
