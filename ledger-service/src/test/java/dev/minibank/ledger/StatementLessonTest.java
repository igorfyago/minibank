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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE STATEMENT · A READ MODEL SHOULD COST WHAT IT SHOWS.
 *
 * The page shows the latest 40 rows. The query used to compute a window
 * function, SUM(amount) OVER (ORDER BY id), across EVERY entry the account
 * ever had · and only then sort, and only then keep 40. The work grew with
 * the customer's whole history for a page whose size never changes, and the
 * front end asked for it once per product card, every two seconds.
 *
 * The fix is the doctrine this bank already teaches: the running balance is
 * a PROJECTION the ledger already maintains (the cached balance, reconciled
 * by the drift audit). Anchor on it and walk backwards through the 40 rows
 * actually fetched. Same numbers, O(40).
 *
 *   lesson 1  the running balance is still exactly right
 *   lesson 2  ... and still right when the newest entry is a credit
 *   lesson 3  the cost does not grow with history
 *
 * And a read model should also SAY WHAT HAPPENED. A statement that has the
 * transaction's own kind in front of it and still guesses the label from a
 * counterparty account is not short of information, it is not using it.
 *
 *   lesson 4  a settled trade reads 'Bought Bitcoin', with size and price
 *   lesson 5  history from the retired direct path reads the same way
 *
 * Requires: docker compose up -d
 */
class StatementLessonTest {

    static final long IGOR = 10;
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
        Shards.nameRegions("eu", "uk");
        server = HttpApi.start(0);
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void down() {
        if (server != null) server.stop(0);
    }

    @BeforeEach
    void freshLedger() throws Exception {
        Fixtures.resetShards();
        Shards.forCustomer(IGOR).createCustomer(IGOR, "igor");
        Products.ensureFor(IGOR);
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 1: every row's running balance matches the ledger, recomputed the slow honest way")
    void lesson1_runningBalanceIsCorrect() throws Exception {
        Shard home = Shards.forCustomer(IGOR);
        home.transferLocal(UUID.randomUUID(), Shard.WORLD, IGOR, eur("500.00"));
        for (int i = 1; i <= 6; i++)
            home.transferLocal(UUID.randomUUID(), IGOR, IGOR + Products.SAVINGS, eur(i + ".00"));
        home.transferLocal(UUID.randomUUID(), Shard.WORLD, IGOR, eur("40.00"));

        List<BigDecimal> afters = afters(statement(IGOR));
        assertSameNumbers(expectedAfters(home, IGOR), afters,
                "the statement's running balance must equal the ledger's");

        // the newest row's balance is, by definition, today's balance
        assertEquals(0, home.balance(IGOR).compareTo(afters.get(0)),
                "the top row must land exactly on the account's current balance");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 2: the walk-back holds for credits and debits alike · sign is not a special case")
    void lesson2_bothDirections() throws Exception {
        Shard home = Shards.forCustomer(IGOR);
        home.transferLocal(UUID.randomUUID(), Shard.WORLD, IGOR, eur("100.00"));
        home.transferLocal(UUID.randomUUID(), IGOR, IGOR + Products.SAVINGS, eur("30.00"));
        home.transferLocal(UUID.randomUUID(), Shard.WORLD, IGOR, eur("7.50"));   // newest is a credit

        assertSameNumbers(expectedAfters(home, IGOR), afters(statement(IGOR)), "main account");

        // and the savings account, whose newest entry is a credit it received
        assertSameNumbers(expectedAfters(home, IGOR + Products.SAVINGS),
                afters(statement(IGOR + Products.SAVINGS)), "savings account");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 3: the page costs what it shows · 200 more entries must not make it slower")
    void lesson3_costDoesNotGrowWithHistory() throws Exception {
        Shard home = Shards.forCustomer(IGOR);
        home.transferLocal(UUID.randomUUID(), Shard.WORLD, IGOR, eur("5000.00"));
        for (int i = 0; i < 20; i++)
            home.transferLocal(UUID.randomUUID(), IGOR, IGOR + Products.SAVINGS, eur("1.00"));

        long shortPlan = rowsRead(home, IGOR);
        assertSameNumbers(expectedAfters(home, IGOR), afters(statement(IGOR)), "correct with a short history");

        for (int i = 0; i < 200; i++)
            home.transferLocal(UUID.randomUUID(), IGOR, IGOR + Products.SAVINGS, eur("1.00"));

        long longPlan = rowsRead(home, IGOR);
        assertSameNumbers(expectedAfters(home, IGOR), afters(statement(IGOR)), "still correct with 10x the history");

        // Ask Postgres, not the clock. The old plan was a WindowAgg over every
        // entry the account had, so this number WAS the history: ~40 then ~240.
        // A laptop timing would be noise; the row count is the actual claim.
        assertTrue(longPlan <= 60,
                "the statement must read only the rows it shows · read " + longPlan
                        + " rows from a 240-entry account (short history read " + shortPlan + ")");
        assertTrue(longPlan <= shortPlan + 20,
                "and reading must not grow with history · short=" + shortPlan + " long=" + longPlan);
    }

    /** the biggest "actual rows" any node of the SHIPPED statement query
     *  touches · EXPLAIN ANALYZE is the only honest witness here */
    private static long rowsRead(Shard home, long account) throws Exception {
        try (Connection c = home.open()) {
            // Gather statistics before asking the planner to choose, because a
            // planner with no statistics is not the planner anything runs in
            // production. This test failed in CI and passed on a laptop for
            // exactly that reason: a developer's database has been analysed by
            // autovacuum hours ago, a CI database is forty seconds old.
            //
            // The difference is not academic and not about table size. With no
            // statistics Postgres estimates 4 matching rows, picks a Bitmap Heap
            // Scan, and materialises EVERY matching row before the Sort and the
            // LIMIT: 240 rows read to show 40. Once analysed it walks the index
            // backwards and stops at 40. Same data, same query, same index.
            //
            // What this lesson claims is that the page costs what it shows. That
            // is a claim about the query's shape and the index behind it, and it
            // cannot be demonstrated while withholding the statistics the
            // planner needs in order to act on either.
            try (var st = c.createStatement()) { st.execute("ANALYZE entries"); }
            return maxActualRows(c, account);
        }
    }

    private static long maxActualRows(Connection c, long account) throws Exception {
        try (var ps = c.prepareStatement("EXPLAIN (ANALYZE, FORMAT JSON) " + HttpApi.STATEMENT_SQL)) {
            ps.setLong(1, account);
            try (var rs = ps.executeQuery()) {
                rs.next();
                String plan = rs.getString(1);
                assertTrue(!plan.contains("WindowAgg"),
                        "a window function over the whole ledger is exactly the regression this guards");
                long max = 0;
                var m = java.util.regex.Pattern.compile("\"Actual Rows\": ([0-9.]+)").matcher(plan);
                while (m.find()) max = Math.max(max, (long) Double.parseDouble(m.group(1)));
                return max;
            }
        }
    }

    // ------------------------------------------------------------------
    /**
     * A STATEMENT LINE NAMES THE EVENT.
     *
     * A settled fill is the only way an asset moves now, and it used to be the
     * one shape this statement could not describe. 'settle:BTC:buy' matched
     * none of the cases, fell to the bottom branch, and got labelled from
     * whichever counterparty leg the LATERAL happened to pick first · which
     * for an asset trade is a broker account whose owner column is the
     * literal string "broker". So a customer who SOLD Apple stock read "Money
     * added", and one who bought Bitcoin read "broker · sent". The transaction
     * knew exactly what it was the entire time; nothing asked it.
     */
    @Test
    @DisplayName("lesson 4: a settled trade says what happened · 'Bought Bitcoin', not 'broker' and not 'Money added'")
    void lesson4_assetMovementsNameTheEvent() throws Exception {
        Shard home = Shards.forCustomer(IGOR);
        home.transferLocal(UUID.randomUUID(), Shard.WORLD, IGOR, eur("1000.00"));

        // a buy and a sell, both through the settlement path · 0.01 BTC at 50k
        Products.settleFill(UUID.randomUUID(), IGOR, "btc", true, eur("0.01"), eur("500.00"));
        Products.settleFill(UUID.randomUUID(), IGOR, "btc", false, eur("0.01"), eur("600.00"));

        String json = statement(IGOR);
        List<String> labels = fields(json, "label");
        List<String> tags = fields(json, "tag");

        // newest first: the sale, then the purchase, then the top-up that
        // funded it. Positional and exact, because "contains" would pass on a
        // statement that also labelled one of them wrongly.
        assertEquals("Sold Bitcoin", labels.get(0), "the sale names itself · got " + labels);
        assertEquals("sell", tags.get(0));
        assertEquals("Bought Bitcoin", labels.get(1), "and so does the purchase · got " + labels);
        assertEquals("buy", tags.get(1));

        // the exact regressions, named. "broker" is what the counterparty
        // fallback produces for a buy; "Money added" is what it produced for a
        // sale, because incoming money was the only thing being consulted.
        assertFalse(labels.contains("broker"),
                "a counterparty account name is not an event · got " + labels);
        assertNotEquals("Money added", labels.get(0),
                "selling bitcoin is not somebody topping up your account · got " + labels);
        // the row that IS a top-up still says so · this vocabulary replaced
        // the guess for asset movements, not for everything
        assertEquals("Money added", labels.get(2), "got " + labels);

        // and the size and price ride along, recovered from the transaction's
        // own legs rather than from a feed · a feed would show today's price
        // beside a purchase made whenever it was made
        List<String> details = fields(json, "detail");
        assertTrue(details.contains("0.01 @ €50000.00"), "bought 0.01 at 50k · got " + details);
        assertTrue(details.contains("0.01 @ €60000.00"), "sold 0.01 at 60k · got " + details);
    }

    // ------------------------------------------------------------------
    /**
     * The retired direct path is gone as a way to buy, but its transactions
     * are still on the books · a statement has to render history it can no
     * longer produce. 'trade:' keeps the same vocabulary as 'settle:',
     * because they were always the same economic event.
     */
    @Test
    @DisplayName("lesson 5: history written by the retired path reads the same · one vocabulary, old rows included")
    void lesson5_retiredPathHistoryStillReads() throws Exception {
        Shard home = Shards.forCustomer(IGOR);
        home.transferLocal(UUID.randomUUID(), Shard.WORLD, IGOR, eur("1000.00"));
        Products.tradeWithoutBroker(UUID.randomUUID(), IGOR, "aapl", true, eur("500.00"), eur("250.00"));

        List<String> labels = fields(statement(IGOR), "label");
        assertTrue(labels.contains("Bought Apple stock"),
                "an old row is still an event, and still names itself · got " + labels);
    }

    // ------------------------------------------------------------------
    private static BigDecimal eur(String v) { return new BigDecimal(v); }

    /** every value of a string field, in row order · scraped without a JSON lib */
    private static List<String> fields(String json, String name) {
        List<String> out = new ArrayList<>();
        var m = java.util.regex.Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(json);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** BigDecimal.equals compares scale too · money cares about value */
    private static void assertSameNumbers(List<BigDecimal> expected, List<BigDecimal> actual, String why) {
        assertEquals(expected.size(), actual.size(), why + " · row count");
        for (int i = 0; i < expected.size(); i++)
            assertEquals(0, expected.get(i).compareTo(actual.get(i)),
                    why + " · row " + i + " expected " + expected.get(i) + " but was " + actual.get(i));
    }

    private String statement(long account) throws Exception {
        HttpResponse<String> r = HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/statement?customer=" + account)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return r.body();
    }

    private long timeStatement(long account) throws Exception {
        statement(account);                       // warm the plan and the pool
        long t0 = System.nanoTime();
        for (int i = 0; i < 5; i++) statement(account);
        return (System.nanoTime() - t0) / 5 / 1_000_000;
    }

    /** the "after" field of every row, newest first · scraped without a JSON lib */
    private static List<BigDecimal> afters(String json) {
        List<BigDecimal> out = new ArrayList<>();
        var m = java.util.regex.Pattern.compile("\"after\":\"(-?[0-9.]+)\"").matcher(json);
        while (m.find()) out.add(new BigDecimal(m.group(1)));
        return out;
    }

    /** the slow, obviously-correct way: replay the ledger from the beginning */
    private static List<BigDecimal> expectedAfters(Shard home, long account) throws Exception {
        List<BigDecimal> running = new ArrayList<>();
        try (Connection c = home.open();
             var ps = c.prepareStatement("SELECT amount FROM entries WHERE account_id = ? ORDER BY id")) {
            ps.setLong(1, account);
            try (var rs = ps.executeQuery()) {
                BigDecimal sum = BigDecimal.ZERO;
                while (rs.next()) {
                    sum = sum.add(rs.getBigDecimal(1));
                    running.add(sum);
                }
            }
        }
        java.util.Collections.reverse(running);                 // newest first, like the API
        return new ArrayList<>(running.subList(0, Math.min(40, running.size())));
    }
}
