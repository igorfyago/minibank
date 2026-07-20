package dev.minibank.broker;

import com.sun.net.httpserver.HttpServer;
import dev.minibank.ledger.AssetRegistry;
import dev.minibank.ledger.Fixtures;
import dev.minibank.ledger.Shard;
import dev.minibank.ledger.Shards;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LIST ON FIRST TRADE · an unlisted OCC contract lists itself, or refuses.
 *
 * The catalog is seeded with two rows and listing is a business decision ·
 * that rule survives. What changes is WHO asks: a customer trading a contract
 * of an ALLOWLISTED underlying's own chain is the business decision, made per
 * contract, verified against the venue's data about that exact contract. The
 * chain says what the contract is (its size, its expiry); this bank only
 * decides whether to carry it.
 *
 *   lesson 1  the first trade lists EXACTLY ONE contract · registry on both
 *             shards first, catalog second, then the order fills. Never the
 *             chain the contract came from.
 *   lesson 2  the second trade lists NOTHING · idempotent, one listing per
 *             instrument forever.
 *   lesson 3  a contract the chain does not carry is REFUSED · no listing,
 *             no order row, no fill. Yahoo echoes unknown expiries back as
 *             silently empty chains, so wrong strike and wrong date land in
 *             this same refusal.
 *   lesson 4  an underlying off the allowlist is refused WITHOUT asking
 *             Yahoo · the upstream is never consulted about symbols this
 *             bank does not carry.
 *   lesson 5  an already-expired contract is refused BEFORE the chain is
 *             fetched · same inclusive boundary as everywhere else.
 *   lesson 6  a slot collision in the registry refuses the LISTING · the
 *             catalog never hears about it, no order exists, and the reason
 *             names the squatter.
 *   lesson 7  a chain row with no stated contract size is refused · a
 *             defaulted multiplier is a 100x cash error, so absence refuses.
 *
 * THE VENUE FILLS EVERYTHING (AlwaysFills) · so every refusal below is the
 * intake gate's doing, not the venue's good judgement.
 *
 * Requires: docker compose up -d   (shards :5434/:5435, control plane :5433)
 */
class ListOnFirstTradeLessonTest {

    static final long IGOR = 10;
    static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

    /** Relative to today, so the fixtures never rot into the past. */
    static final LocalDate EXPIRY = LocalDate.now(ZoneOffset.UTC).plusMonths(6);
    static final LocalDate EXPIRY2 = EXPIRY.plusMonths(1);
    static final long EPOCH = EXPIRY.toEpochDay() * 86_400L;
    static final long EPOCH2 = EXPIRY2.toEpochDay() * 86_400L;

    static final String CONTRACT = occ("XSP", EXPIRY, 'C', 700);
    static final String CONTRACT_705 = occ("XSP", EXPIRY, 'C', 705);
    static final String CONTRACT_710 = occ("XSP", EXPIRY, 'C', 710);
    static final String NOT_IN_CHAIN = occ("XSP", EXPIRY, 'C', 999);
    static final String EXPIRED = occ("XSP", LocalDate.now(ZoneOffset.UTC).minusDays(1), 'C', 700);
    static final String UNKNOWN_ROOT = occ("TSLA", EXPIRY, 'C', 200);

    static final String[] MINE = {CONTRACT, CONTRACT_705, CONTRACT_710, NOT_IN_CHAIN,
            EXPIRED, UNKNOWN_ROOT, "SQUAT"};

    static final ExpiredContractTradeGateLessonTest.AlwaysFills VENUE =
            new ExpiredContractTradeGateLessonTest.AlwaysFills();
    static final StubChain SOURCE = new StubChain();
    static Broker broker;
    static HttpServer server;
    static int port;
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    static void boot() throws Exception {
        Shards.boot(
                System.getenv().getOrDefault("MINIBANK_SHARD0_URL", "jdbc:postgresql://localhost:5434/minibank"),
                System.getenv().getOrDefault("MINIBANK_SHARD1_URL", "jdbc:postgresql://localhost:5435/minibank"),
                "minibank", "minibank", 5);
        for (Shard s : Shards.all()) s.createSchema();
        BrokerDb.createOwnDatabase();
        broker = new Broker(VENUE);
        server = new BrokerApi(broker).start(0);
        port = server.getAddress().getPort();
        OptionChain.setSource(SOURCE);
    }

    @AfterAll
    static void down() {
        if (server != null) server.stop(0);
        OptionChain.setSource(null);
    }

    @BeforeEach
    void fresh() throws Exception {
        Fixtures.resetBrokerDb();
        try (Connection c = BrokerDb.open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM instruments WHERE symbol = ANY(?)")) {
            ps.setArray(1, c.createArrayOf("text", MINE));
            ps.executeUpdate();
        }
        Catalog.seed();
        for (Shard s : Shards.all()) {
            try (Connection c = s.open()) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM asset_accounts WHERE symbol = ANY(?)")) {
                    ps.setArray(1, c.createArrayOf("text", MINE));
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM asset_slots WHERE symbol = ANY(?)")) {
                    ps.setArray(1, c.createArrayOf("text", MINE));
                    ps.executeUpdate();
                }
            }
        }
        VENUE.reset();
        SOURCE.reset();
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 1: the first trade lists EXACTLY ONE contract, on both shards, before any money moves")
    void lesson1_firstTradeLists() throws Exception {
        assertNull(Catalog.find(CONTRACT), "unlisted before");
        assertFalse(AssetRegistry.isRegisteredEverywhere(CONTRACT));
        int catalogBefore = Catalog.all().size();
        long[] slotsBefore = slotCounts();

        Resp r = trade(CONTRACT, "1");
        assertEquals(200, r.status(), r.body());
        assertTrue(r.body().contains("\"result\":\"filled\""), r.body());

        Catalog.Instrument listed = Catalog.find(CONTRACT);
        assertNotNull(listed, "the trade listed it");
        assertEquals("option", listed.kind());
        assertEquals(0, new java.math.BigDecimal("100").compareTo(listed.multiplier()),
                "the multiplier came from the chain's REGULAR contract size, not from a default");
        assertEquals(EXPIRY, listed.expiresOn(), "the expiry both the symbol and the chain agree on");

        assertTrue(AssetRegistry.isRegisteredEverywhere(CONTRACT),
                "THE POINT: listed on EVERY shard before the fill · an instrument present in "
                        + "only one place is exactly the asymmetry the registry exists to close");
        assertEquals(catalogBefore + 1, Catalog.all().size(), "the catalog grew by exactly one");
        long[] slotsAfter = slotCounts();
        for (int i = 0; i < slotsAfter.length; i++)
            assertEquals(slotsBefore[i] + 1, slotsAfter[i],
                    "and each shard's registry grew by exactly one · never the whole chain");
        assertNull(Catalog.find(CONTRACT_705),
                "the chain's OTHER contracts stayed unlisted · one trade, one instrument");
        assertEquals(1, VENUE.placements, "and the venue was consulted after the listing, once");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 2: the second trade lists NOTHING · idempotent")
    void lesson2_secondTradeListsNothing() throws Exception {
        assertEquals(200, trade(CONTRACT, "1").status());
        int catalogAfterFirst = Catalog.all().size();
        long[] slotsAfterFirst = slotCounts();

        Resp r = trade(CONTRACT, "2");
        assertEquals(200, r.status(), r.body());
        assertTrue(r.body().contains("\"result\":\"filled\""), r.body());

        assertEquals(catalogAfterFirst, Catalog.all().size(), "no second catalog row");
        long[] slotsAfterSecond = slotCounts();
        for (int i = 0; i < slotsAfterSecond.length; i++)
            assertEquals(slotsAfterFirst[i], slotsAfterSecond[i], "no second registry row on any shard");
        assertEquals(2, VENUE.placements, "both orders reached the venue");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 3: a contract the chain does not carry is REFUSED · no listing, no order, no fill")
    void lesson3_notInChainRefused() throws Exception {
        Resp r = trade(NOT_IN_CHAIN, "1");
        assertEquals(409, r.status(), r.body());
        assertTrue(r.body().contains("not in the"), r.body());

        assertNull(Catalog.find(NOT_IN_CHAIN), "nothing listed");
        assertFalse(registeredAnywhere(NOT_IN_CHAIN), "on either shard");
        assertEquals(0, orderCount(), "NO ORDER ROW · the gate is before the money path, not inside it");
        assertEquals(0, fillCount(), "and therefore nothing that could ever settle");
        assertEquals(0, VENUE.placements, "the venue never heard about it");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 4: an underlying off the allowlist is refused WITHOUT consulting Yahoo")
    void lesson4_unknownUnderlyingNeverReachesUpstream() throws Exception {
        int upstreamBefore = SOURCE.chainCalls;
        Resp r = trade(UNKNOWN_ROOT, "1");
        assertEquals(409, r.status(), r.body());
        assertTrue(r.body().contains("not supported"), r.body());
        assertEquals(upstreamBefore, SOURCE.chainCalls,
                "THE POINT: free-form symbols never reach the server-to-Yahoo path · "
                        + "the allowlist is what keeps this from being an open proxy");
        assertEquals(0, orderCount());
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 5: an already-expired contract is refused BEFORE the chain is fetched")
    void lesson5_expiredRefusedBeforeFetch() throws Exception {
        int upstreamBefore = SOURCE.chainCalls;
        Resp r = trade(EXPIRED, "1");
        assertEquals(409, r.status(), r.body());
        assertTrue(r.body().contains("expired"), r.body());
        assertEquals(upstreamBefore, SOURCE.chainCalls, "no fetch for a contract that is already dead");
        assertNull(Catalog.find(EXPIRED));
        assertEquals(0, orderCount());
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 6: a slot collision refuses the LISTING · the registry's loud failure, surfaced to the customer")
    void lesson6_slotCollisionRefuses() throws Exception {
        // squat on the slot this contract's symbol derives · the planted row
        // obeys the capacity constraint, it is simply somebody else's slot
        long slot = fold(CONTRACT_705);
        long base = AssetRegistry.ASSET_BASE + slot * AssetRegistry.SLOT_STRIDE;
        try (Connection c = Shards.s(0).open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO asset_slots(symbol, currency, label, slot, legacy_offset,
                                             broker_account, clearing_account, multiplier, kind, expires_on)
                     VALUES ('SQUAT','SQUAT','squatter',?,NULL,?,?,1,'equity',NULL)""")) {
            ps.setLong(1, slot);
            ps.setLong(2, base + AssetRegistry.SUFFIX_BROKER);
            ps.setLong(3, base + AssetRegistry.SUFFIX_CLEARING);
            ps.executeUpdate();
        }

        Resp r = trade(CONTRACT_705, "1");
        assertEquals(409, r.status(), r.body());
        assertTrue(r.body().contains("slot"), "the reason names the collision: " + r.body());

        assertNull(Catalog.find(CONTRACT_705),
                "the registry goes FIRST, so a refused registry means the catalog never heard of it");
        assertFalse(registeredAnywhere(CONTRACT_705), "and no shard lists it");
        assertEquals(0, orderCount(), "no order");
        assertEquals(0, fillCount(), "no fill");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 7: a chain row with NO stated contract size is refused · absence never defaults to 100")
    void lesson7_absentContractSizeRefuses() throws Exception {
        Resp r = trade(CONTRACT_710, "1");
        assertEquals(409, r.status(), r.body());
        assertTrue(r.body().contains("contract size"), r.body());
        assertNull(Catalog.find(CONTRACT_710));
        assertEquals(0, orderCount());
    }

    // ------------------------------------------------------------------ helpers

    record Resp(int status, String body) {}

    private static Resp trade(String symbol, String qty) throws Exception {
        String body = "{\"clientOrderId\":\"" + UUID.randomUUID() + "\",\"customer\":" + IGOR
                + ",\"symbol\":\"" + symbol + "\",\"side\":\"buy\",\"qty\":\"" + qty + "\"}";
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/orders"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.body());
    }

    static String occ(String root, LocalDate expiry, char cp, int strike) {
        return root + expiry.format(YYMMDD) + cp + String.format("%08d", strike * 1000);
    }

    /** The registry's fold, replicated · SlotCapacityLessonTest lesson 1 pins
     *  this replica against derivedSlot itself, so planting with it squats on
     *  the exact slot register() will derive. */
    static long fold(String symbol) {
        long h = 0xcbf29ce484222325L;
        for (byte b : symbol.trim().toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xffL);
            h *= 0x100000001b3L;
        }
        return Math.floorMod(h, AssetRegistry.SLOT_LIMIT - AssetRegistry.FIRST_DERIVED_SLOT)
                + AssetRegistry.FIRST_DERIVED_SLOT;
    }

    private static boolean registeredAnywhere(String symbol) throws SQLException {
        for (Shard s : Shards.all()) {
            try (Connection c = s.open()) {
                AssetRegistry.bySymbol(c, symbol);
                return true;
            } catch (AssetRegistry.UnknownAsset ignored) {
                // not on this shard
            }
        }
        return false;
    }

    private static long[] slotCounts() throws SQLException {
        long[] out = new long[Shards.all().size()];
        int i = 0;
        for (Shard s : Shards.all()) {
            try (Connection c = s.open();
                 PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM asset_slots");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                out[i++] = rs.getLong(1);
            }
        }
        return out;
    }

    private static int orderCount() throws SQLException {
        return countOf("orders");
    }

    private static int fillCount() throws SQLException {
        return countOf("fills");
    }

    private static int countOf(String table) throws SQLException {
        try (Connection c = BrokerDb.open();
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM " + table);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // ------------------------------------------------------------------
    /** The chain, from a body this test wrote · the observed v7 shape,
     *  including the trap: an expiry Yahoo does not list comes back ECHOED
     *  with empty calls and puts, never an error. */
    static final class StubChain implements OptionChain.Source {
        int chainCalls;
        String lastSymbol;
        Long lastDate;
        boolean fail;

        void reset() {
            chainCalls = 0;
            lastSymbol = null;
            lastDate = null;
            lastRangeQuery = null;
            fail = false;
        }

        @Override
        public String chainBody(String yahooSymbol, Long date) {
            chainCalls++;
            lastSymbol = yahooSymbol;
            lastDate = date;
            if (fail) throw new IllegalStateException("stubbed outage");
            if (!"^XSP".equals(yahooSymbol))
                return "{\"optionChain\":{\"result\":[],\"error\":null}}";
            if (date != null && date == EPOCH2) return body(EPOCH2, rows(EPOCH2), "[]");
            if (date != null && date != EPOCH)
                return body(date, "[]", "[]");     // the echo trap, verbatim
            return body(EPOCH, rows(EPOCH), putRows(EPOCH));
        }

        String lastRangeQuery;

        @Override
        public String chartBody(String yahooSymbol, String rangeQuery) {
            chainCalls++;
            lastRangeQuery = rangeQuery;
            if (fail) throw new IllegalStateException("stubbed outage");
            return "{\"chart\":{\"result\":[{\"meta\":{\"currency\":\"USD\",\"symbol\":\"^XSP\","
                    + "\"regularMarketPrice\":746.81},\"timestamp\":[1752000000,1752000300,1752000600],"
                    + "\"indicators\":{\"quote\":[{\"close\":[746.1,null,746.5]}]}}],\"error\":null}}";
        }

        private static String body(long expiry, String calls, String puts) {
            return "{\"optionChain\":{\"result\":[{\"underlyingSymbol\":\"^XSP\","
                    + "\"expirationDates\":[" + EPOCH + "," + EPOCH2 + "],"
                    + "\"strikes\":[700.0,705.0,710.0],"
                    // the day fields, exactly as observed live in the quote
                    // block (2026-07-20) · the proxy lessons assert they
                    // survive the trip to the screen
                    + "\"quote\":{\"language\":\"en-US\",\"marketState\":\"REGULAR\",\"currency\":\"USD\","
                    + "\"regularMarketChange\":1.04,\"regularMarketChangePercent\":0.14,"
                    + "\"regularMarketTime\":1784559976,"
                    + "\"regularMarketPrice\":746.81,\"shortName\":\"S&P 500 MINI SPX OPTIONS INDEX\"},"
                    + "\"options\":[{\"expirationDate\":" + expiry + ",\"hasMiniOptions\":false,"
                    + "\"calls\":" + calls + ",\"puts\":" + puts + "}]}],\"error\":null}}";
        }

        /** 700: the full observed row. 705: volume and openInterest ABSENT,
         *  bid and ask ABSENT · the sometimes-missing fields, missing.
         *  710: no contractSize · the row lesson 7 refuses to list from. */
        private static String rows(long expiry) {
            String c700 = occ("XSP", LocalDate.ofEpochDay(expiry / 86_400), 'C', 700);
            String c705 = occ("XSP", LocalDate.ofEpochDay(expiry / 86_400), 'C', 705);
            String c710 = occ("XSP", LocalDate.ofEpochDay(expiry / 86_400), 'C', 710);
            return "[{\"contractSymbol\":\"" + c700 + "\",\"strike\":700.0,\"currency\":\"USD\","
                    + "\"lastPrice\":3.1,\"change\":0.11,\"percentChange\":3.68,\"volume\":1857,"
                    + "\"openInterest\":225,\"bid\":3.0,\"ask\":3.2,\"contractSize\":\"REGULAR\","
                    + "\"expiration\":" + expiry + ",\"lastTradeDate\":1784559953,"
                    + "\"impliedVolatility\":0.12,\"inTheMoney\":true},"
                    + "{\"contractSymbol\":\"" + c705 + "\",\"strike\":705.0,\"currency\":\"USD\","
                    + "\"lastPrice\":1.9,\"change\":-0.05,\"percentChange\":-2.56,"
                    + "\"contractSize\":\"REGULAR\",\"expiration\":" + expiry + ","
                    + "\"lastTradeDate\":1784559953,\"impliedVolatility\":0.10,\"inTheMoney\":false},"
                    + "{\"contractSymbol\":\"" + c710 + "\",\"strike\":710.0,\"currency\":\"USD\","
                    + "\"lastPrice\":1.2,\"change\":0.0,\"percentChange\":0.0,\"volume\":4,"
                    + "\"openInterest\":9,\"bid\":1.1,\"ask\":1.3,"
                    + "\"expiration\":" + expiry + ",\"lastTradeDate\":1784559953,"
                    + "\"impliedVolatility\":0.09,\"inTheMoney\":false}]";
        }

        private static String putRows(long expiry) {
            String p700 = occ("XSP", LocalDate.ofEpochDay(expiry / 86_400), 'P', 700);
            return "[{\"contractSymbol\":\"" + p700 + "\",\"strike\":700.0,\"currency\":\"USD\","
                    + "\"lastPrice\":2.4,\"change\":-0.2,\"percentChange\":-7.69,\"volume\":140,"
                    + "\"openInterest\":88,\"bid\":2.3,\"ask\":2.5,\"contractSize\":\"REGULAR\","
                    + "\"expiration\":" + expiry + ",\"lastTradeDate\":1784559953,"
                    + "\"impliedVolatility\":0.11,\"inTheMoney\":false}]";
        }
    }
}
