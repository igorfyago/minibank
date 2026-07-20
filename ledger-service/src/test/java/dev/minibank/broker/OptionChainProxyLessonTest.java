package dev.minibank.broker;

import com.sun.net.httpserver.HttpServer;
import dev.minibank.ledger.Cache;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE CHAIN PROXY · gated, validated, cached, and honest about absence.
 *
 * The browser never talks to Yahoo. These routes serve the chain screen from
 * this process, and three properties make that safe to expose:
 *
 *   THE ALLOWLIST   only OptionChain's hardcoded underlyings are ever
 *                   forwarded upstream · anything else is a 404 answered
 *                   without a single upstream call, so the route cannot be
 *                   enlisted as an open proxy with our IP on it.
 *   VALIDATION      Yahoo echoes an unknown ?date= back as a silently EMPTY
 *                   chain (200, no error) · so an expiry is checked against
 *                   the chain's own expiration list and refused with a 400
 *                   BEFORE it is ever forwarded. The screen gets a reason,
 *                   never a void.
 *   ABSENCE         fields Yahoo omits stay omitted. volume and openInterest
 *                   are absent per row on real chains; emitting 0 would state
 *                   "traded, none" about a row that stated nothing. An
 *                   unpriced underlying (bare XSP's ECNQUOTE shape) is null,
 *                   never zero.
 *
 *   lesson 1  the underlyings route serves the allowlist, verbatim
 *   lesson 2  the chain payload · trimmed rows, absent fields absent
 *   lesson 3  an unknown underlying is 404 and Yahoo is NEVER consulted
 *   lesson 4  an expiry the chain does not list is 400, and the bogus date
 *             is never forwarded upstream
 *   lesson 5  upstream down: nothing cached is a 502 · something cached is
 *             served MARKED "cached", the serve-stale doctrine
 *   lesson 6  the underlying's chart · same gate, market's own currency,
 *             gaps stay gaps
 *
 * Requires: docker compose up -d   (control-plane Postgres on :5433)
 */
class OptionChainProxyLessonTest {

    static final long EPOCH = ListOnFirstTradeLessonTest.EPOCH;
    static final long EPOCH2 = ListOnFirstTradeLessonTest.EPOCH2;

    static final ListOnFirstTradeLessonTest.StubChain SOURCE = new ListOnFirstTradeLessonTest.StubChain();
    static HttpServer server;
    static int port;
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    static void boot() throws Exception {
        BrokerDb.createOwnDatabase();
        server = new BrokerApi(new Broker(new ExpiredContractTradeGateLessonTest.AlwaysFills())).start(0);
        port = server.getAddress().getPort();
        OptionChain.setSource(SOURCE);
    }

    @AfterAll
    static void down() {
        if (server != null) server.stop(0);
        OptionChain.setSource(null);
    }

    @BeforeEach
    void cold() {
        SOURCE.reset();
        OptionChain.resetLocalCaches();
        // another lesson in this suite turns Redis on for the whole JVM, and
        // these lessons assert what a COLD serve does · drop the shared keys
        // so a previous run's payload cannot answer on the stub's behalf
        Cache.invalidate("options:chain", "XSP:front");
        Cache.invalidate("options:chain", "XSP:" + EPOCH2);
        Cache.invalidate("options:uchart", "XSP:1d");
        Cache.invalidate("options:uchart", "XSP:3mo");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 1: the underlyings route serves the allowlist · the UI picks, it never types")
    void lesson1_underlyings() throws Exception {
        Resp r = get("/api/options/underlyings");
        assertEquals(200, r.status());
        assertEquals("{\"underlyings\":[\"XSP\",\"AAPL\"]}", r.body(),
                "the exact allowlist · a symbol not in this answer has no route to Yahoo");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 2: the chain payload · trimmed to what the screen draws, absent fields ABSENT")
    void lesson2_chainPayload() throws Exception {
        Resp r = get("/api/options/chain?underlying=XSP");
        assertEquals(200, r.status(), r.body());
        String b = r.body();
        assertTrue(b.contains("\"fetched\":\"live\""), "a fresh fetch says so");
        assertTrue(b.contains("\"expirations\":[" + EPOCH + "," + EPOCH2 + "]"),
                "the expiry list rides along · it is how the screen offers other expiries");
        assertTrue(b.contains("\"quote\":{\"price\":\"746.81\",\"change\":\"1.04\","
                        + "\"changePercent\":\"0.14\",\"time\":1784559976,\"currency\":\"USD\","
                        + "\"marketState\":\"REGULAR\"}"),
                "the underlying's mark and its day, as the quote stated them · " + b);

        // the full row carries what Yahoo stated
        assertTrue(b.contains("\"volume\":1857"), "stated volume passes through");
        assertTrue(b.contains("\"openInterest\":225"), "stated open interest passes through");

        // THE 705 ROW: Yahoo omitted volume, openInterest, bid and ask · the
        // payload omits the counts and nulls the prices. NOWHERE does an
        // absent number become a zero.
        String row705 = rowOf(b, "C00705000");
        assertFalse(row705.contains("\"volume\""), "an absent count is an ABSENT KEY: " + row705);
        assertFalse(row705.contains("\"openInterest\""), "same for open interest");
        assertTrue(row705.contains("\"bid\":null"), "an absent price is null, a gap the screen draws as a gap");
        assertFalse(b.contains("\"volume\":0"), "no invented zeros anywhere in the payload");
        assertFalse(b.contains("\"openInterest\":0"), "none");

        assertTrue(b.contains("P00700000"), "puts came too");
        assertTrue(b.contains("\"strike\":\"700.0\""), "strikes as plain strings, exactly as stated");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 3: an unknown underlying is 404 and Yahoo is NEVER consulted · not an open proxy")
    void lesson3_allowlistGate() throws Exception {
        int upstream = SOURCE.chainCalls;
        Resp r = get("/api/options/chain?underlying=GME");
        assertEquals(404, r.status());
        assertEquals(upstream, SOURCE.chainCalls,
                "THE POINT: the refusal costs zero upstream calls · a free-form symbol has no "
                        + "path to Yahoo through this service");
        assertEquals(200, get("/api/options/chain?underlying=xsp").status(),
                "and case is not part of an underlying's name");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 4: an expiry the chain does not list is 400 · the bogus date is never forwarded")
    void lesson4_expiryValidated() throws Exception {
        Resp r = get("/api/options/chain?underlying=XSP&expiry=1700000000");
        assertEquals(400, r.status(), r.body());
        assertTrue(r.body().contains("not an expiration"), r.body());
        assertNull(SOURCE.lastDate,
                "THE POINT: Yahoo echoes a bogus date back as a silently empty chain, so the only "
                        + "fetch made was the FRONT chain (date null) that the validation read · "
                        + "the bad date itself never went upstream");

        Resp ok = get("/api/options/chain?underlying=XSP&expiry=" + EPOCH2);
        assertEquals(200, ok.status(), ok.body());
        assertTrue(ok.body().contains("\"expiry\":" + EPOCH2), "the requested expiry's chain");
        assertEquals(EPOCH2, SOURCE.lastDate, "and THAT date was forwarded, being real");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 5: upstream down · 502 when nothing is cached, the last good chain MARKED cached when one exists")
    void lesson5_serveStaleHonestly() throws Exception {
        SOURCE.fail = true;
        assertEquals(502, get("/api/options/chain?underlying=XSP").status(),
                "cold and dark: an unavailability, said out loud · never an empty chain wearing a 200");

        SOURCE.fail = false;
        assertTrue(get("/api/options/chain?underlying=XSP").body().contains("\"fetched\":\"live\""));

        // the shared cache would answer before the loader runs · drop it so
        // the next request actually exercises the upstream-down path
        Cache.invalidate("options:chain", "XSP:front");
        SOURCE.fail = true;
        Resp stale = get("/api/options/chain?underlying=XSP");
        assertEquals(200, stale.status());
        assertTrue(stale.body().contains("\"fetched\":\"cached\""),
                "the last good chain, MARKED · a stale chain with its age admitted beats an error, "
                        + "and beats an unmarked one by more");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 6: the underlying's chart · same gate, the market's own currency, gaps stay gaps")
    void lesson6_underlyingChart() throws Exception {
        assertEquals(404, get("/api/options/chart?underlying=GME").status(), "same allowlist");

        Resp r = get("/api/options/chart?underlying=XSP");
        assertEquals(200, r.status(), r.body());
        assertTrue(r.body().contains("\"currency\":\"USD\""),
                "the chain screen quotes the option market's own currency · no FX opinion inside "
                        + "a strike comparison");
        assertTrue(r.body().contains("[1752000000000,746.1]"), "points in ms, price as stated");
        assertTrue(r.body().contains("[1752000600000,746.5]"));
        assertFalse(r.body().contains("null,"),
                "the null close was SKIPPED · a gap in the series is a gap, not a zero and not a "
                        + "carried-forward guess");
        assertTrue(r.body().contains("\"range\":\"1d\""),
                "no range asked for means the intraday default, and the payload says which");
        assertEquals("range=1d&interval=5m", SOURCE.lastRangeQuery,
                "the default really is the observed intraday pair");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 7: chart ranges are an ALLOWLIST · a real one forwards its own pair, junk is 400 and never forwarded")
    void lesson7_chartRanges() throws Exception {
        Resp r = get("/api/options/chart?underlying=XSP&range=3mo");
        assertEquals(200, r.status(), r.body());
        assertTrue(r.body().contains("\"range\":\"3mo\""));
        assertEquals("range=3mo&interval=1d", SOURCE.lastRangeQuery,
                "the named range maps to the venue's own pair · never the string the caller typed");

        int upstream = SOURCE.chainCalls;
        Resp bad = get("/api/options/chart?underlying=XSP&range=max");
        assertEquals(400, bad.status(), bad.body());
        assertTrue(bad.body().contains("unknown chart range"), bad.body());
        assertEquals(upstream, SOURCE.chainCalls,
                "THE POINT: a range off the allowlist costs zero upstream calls · the range rides "
                        + "into Yahoo's URL, so free-form values must have no path there");
    }

    // ------------------------------------------------------------------ helpers

    record Resp(int status, String body) {}

    private static Resp get(String path) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.body());
    }

    /** The one row object mentioning this contract suffix. */
    private static String rowOf(String body, String suffix) {
        int at = body.indexOf(suffix);
        assertTrue(at >= 0, "no row for " + suffix);
        int start = body.lastIndexOf('{', at);
        int end = body.indexOf('}', at);
        return body.substring(start, end + 1);
    }
}
