package dev.minibank.ledger;

import com.sun.net.httpserver.HttpServer;
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
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ONE NUMBER FOR THE WHOLE CUSTOMER · /api/networth.
 *
 * The bank already holds nearly every part of a Revolut-style net worth: the
 * main and savings balances in the ledger, the holdings valued at the
 * refresh-ahead marks, the card debt as a fold over the card and holds
 * accounts, the loan balance. What it never did was add them up in ONE place.
 * The App tab did the sum in the browser instead, from /api/portfolio's
 * fields, which is parallel arithmetic in the one spot the server cannot
 * audit.
 *
 *   lesson 1  the total is the ledger-derived sum, exactly · every leg read
 *             at request time, none recomputed in parallel
 *   lesson 2  card debt NETS the holds: an uncaptured authorization is not
 *             debt yet, and the strip must not claim it is
 *   lesson 3  a liability is a negative amount that says it is a liability
 *   lesson 4  a stale mark surfaces its age · the total's provenance travels
 *             with the total, same doctrine as the Investments tile
 *
 * Requires: docker compose up -d   (shards :5434/:5435, redis :6379)
 */
class NetWorthLessonTest {

    static final long IGOR = 10;

    /** listed by this test alone, and priced by nobody upstream · the mark it
     *  is valued at is the one this test plants in the shared store */
    static final String SYM = "ZZNW" + (System.nanoTime() % 100000);

    /** the planted mark: €100.00, observed five minutes ago · stale on
     *  purpose, so its age has something to say */
    static final BigDecimal MARK = new BigDecimal("100.00");
    static final long MARK_AGE_MS = 300_000;

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
        // the marks live in the shared store · the compose stack has carried
        // Redis all along, and the seeded price below is what makes the
        // invested leg deterministic instead of whatever Yahoo says today
        Cache.init(System.getenv().getOrDefault("REDIS_URL", "redis://localhost:6379"));
        org.junit.jupiter.api.Assumptions.assumeTrue(Cache.enabled(),
                "these lessons plant a mark in the shared store, so they need the compose Redis");
        bank = HttpApi.start(0);
        port = bank.getAddress().getPort();
    }

    @AfterAll
    static void down() {
        if (bank != null) bank.stop(0);
    }

    /**
     * The seeded customer, the same shape every lesson reads:
     *   funded 10000 · 200 moved to savings · 2 units of SYM bought for 300 ·
     *   a 500 mortgage · 50 of card spend CAPTURED · 30 more only AUTHORIZED.
     * So: main 10000, savings 200, invested 200 at the planted mark,
     * card debt 50 (the 30 hold nets out), loan -500 · total 9850.
     */
    @BeforeEach
    void seededWorld() throws Exception {
        Fixtures.resetShards();
        Shards.forCustomer(IGOR).createCustomer(IGOR, "igor");
        Products.ensureFor(IGOR);
        Shards.forCustomer(IGOR).transferLocal(UUID.randomUUID(), Shard.WORLD, IGOR, new BigDecimal("10000.00"));
        Shards.forCustomer(IGOR).transferLocal(UUID.randomUUID(), IGOR, IGOR + Products.SAVINGS, new BigDecimal("200.00"));

        AssetRegistry.register(SYM, SYM, "the net worth lesson's own listing", BigDecimal.ONE, "equity", null);
        Products.settleFill(UUID.randomUUID(), IGOR, SYM, true, new BigDecimal("2"), new BigDecimal("300.00"));

        Products.mortgage(UUID.randomUUID(), IGOR, new BigDecimal("500.00"));

        UUID captured = UUID.randomUUID();
        Products.authorize(captured, IGOR, new BigDecimal("50.00"));
        Products.capture(captured, IGOR);
        Products.authorize(UUID.randomUUID(), IGOR, new BigDecimal("30.00"));   // never captured

        // the mark, PLANTED: a real price from five minutes ago, in the shared
        // store where refresh-ahead would have put it · encode shape is
        // price|usd|source|prevClose|asOfMillis, and decode() relabels
        // anything past the freshness window as 'cached' with its true age
        PriceFeed.resetLocalCaches();
        Cache.put(PriceFeed.NS, SYM.toLowerCase(java.util.Locale.ROOT), 3600,
                MARK.toPlainString() + "|110.00|live|95.00|" + (System.currentTimeMillis() - MARK_AGE_MS));
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("lesson 1: the total is the ledger-derived sum · exactly, with every leg read at request time")
    void lesson1_totalIsTheLedgerSum() throws Exception {
        String body = get("/api/networth?customer=" + IGOR);

        // the expected total is DERIVED FROM THE LEDGER by this test, through
        // the same reads the bank itself trusts · not hand-summed from the
        // seed script, so a seeding change cannot silently split the two
        BigDecimal expected;
        try (Connection c = Shards.forCustomer(IGOR).open()) {
            expected = Ledger.cachedBalanceOn(c, IGOR)
                    .add(Ledger.cachedBalanceOn(c, IGOR + Products.SAVINGS))
                    .add(new BigDecimal("2").multiply(MARK))            // units at the planted mark
                    .add(Credit.postedDebtAt(c, IGOR, Instant.now()))   // card + holds · negative
                    .add(Ledger.cachedBalanceOn(c, IGOR + Products.LOAN));
        }
        assertEquals(0, expected.compareTo(num(body, "total")),
                "THE POINT: one sum, made by the server from the ledger at request time. The "
                + "browser used to assemble this number itself from /api/portfolio's fields, "
                + "which is the parallel arithmetic this bank keeps deleting.");
        assertEquals(0, new BigDecimal("9850").compareTo(num(body, "total")),
                "and the concrete figure the seed implies · 10000 + 200 + 200 - 50 - 500");
    }

    @Test
    @DisplayName("lesson 2: card debt NETS the holds · an uncaptured authorization is not debt")
    void lesson2_cardDebtNetsTheHolds() throws Exception {
        String body = get("/api/networth?customer=" + IGOR);

        assertEquals(0, new BigDecimal("-50").compareTo(amount(body, "card")),
                "THE POINT: 50 was captured and 30 is only held. An authorization moved money "
                + "card -> holds inside the customer's own shelf, so reading the card account "
                + "alone says -80 · a debt figure that counts money the cafe may never take. "
                + "Credit.postedDebtAt sums card + holds for exactly this reason, and the strip "
                + "must reuse that fold, not fork its own.");
    }

    @Test
    @DisplayName("lesson 3: a liability is a negative amount that says so")
    void lesson3_liabilitiesAreNegativeAndNamed() throws Exception {
        String body = get("/api/networth?customer=" + IGOR);

        assertTrue(amount(body, "card").signum() < 0, "card debt is money the customer owes");
        assertTrue(amount(body, "loan").signum() < 0, "so is the loan");
        assertEquals("liability", kind(body, "card"),
                "and the payload SAYS which rows subtract · the screen renders liabilities in "
                + "red because the server said liability, not because it noticed a minus sign");
        assertEquals("liability", kind(body, "loan"));
        assertEquals("asset", kind(body, "main"));
        assertEquals("asset", kind(body, "invested"));
        assertEquals(0, new BigDecimal("-500").compareTo(amount(body, "loan")),
                "the mortgage: loan -500, main +500, in one commit · borrowing changed the "
                + "total by nothing, and the breakdown shows both sides of why");
    }

    @Test
    @DisplayName("lesson 4: a stale mark surfaces its age · the total's provenance travels with it")
    void lesson4_staleMarksSurfaceTheirAge() throws Exception {
        String body = get("/api/networth?customer=" + IGOR);

        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"priceAgeSeconds\"\\s*:\\s*(\\d+)").matcher(body);
        assertTrue(m.find(), "the age is a NUMBER when a mark is stale, not null · in " + body);
        long age = Long.parseLong(m.group(1));
        assertTrue(age >= (MARK_AGE_MS / 1000) - 5 && age <= (MARK_AGE_MS / 1000) + 120,
                "THE POINT: the invested leg was valued at a five-minute-old mark, and the "
                + "total says so. A net worth rendered at balance size is read as current; "
                + "refresh-ahead made serving old marks normal, and that trade is only honest "
                + "if the age rides along · same doctrine as the Investments tile. Got " + age);
    }

    // ------------------------------------------------------------------ helpers

    private static String get(String path) throws Exception {
        HttpResponse<String> r = HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return r.body();
    }

    /** a top-level string-encoded number · the money-as-string convention */
    private static BigDecimal num(String body, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*\"(-?[0-9.]+)\"").matcher(body);
        if (!m.find()) throw new AssertionError("no numeric \"" + field + "\" in " + body);
        return new BigDecimal(m.group(1));
    }

    /** the amount of one breakdown row, found by its label */
    private static BigDecimal amount(String body, String label) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\{\"label\":\"" + label + "\",\"amount\":\"(-?[0-9.]+)\"").matcher(body);
        if (!m.find()) throw new AssertionError("no breakdown row labeled \"" + label + "\" in " + body);
        return new BigDecimal(m.group(1));
    }

    /** the kind of one breakdown row, found by its label */
    private static String kind(String body, String label) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\{\"label\":\"" + label + "\",\"amount\":\"?[^,]*\"?,\"kind\":\"(asset|liability)\"")
                .matcher(body);
        if (!m.find()) throw new AssertionError("no kind on the row labeled \"" + label + "\" in " + body);
        return m.group(1);
    }
}
