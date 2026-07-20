package dev.minibank.ledger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINICREDIT · THE CREDIT LIFECYCLE IS A FOLD OVER THE LEDGER.
 *
 * The card already worked: spend, hold, capture, release, repay. What it did
 * not have was TIME. A real credit card divides time into statement cycles,
 * charges interest on what you carry past the grace window, demands a
 * minimum payment, and lets the limit move per customer. minicredit adds all
 * four · and the point of every lesson here is that NONE of it is new state.
 * There is no cycle table and no interest accrual job: every cycle fact is
 * recomputed from the append-only entries table, and every charge is a
 * posted double-entry transaction behind a deterministic id.
 *
 *   lesson 1  a cycle is a UTC calendar month · its closing balance is a
 *             SUM over entries before a fixed instant, recomputable forever
 *   lesson 2  interest posts ONCE · the deterministic tx id makes a
 *             recompute lose the primary-key claim, so double-charging is
 *             impossible by construction, not by scheduling care
 *   lesson 3  full repayment in grace means ZERO interest and NO
 *             transaction at all · grace is the min() of two caps
 *   lesson 4  a missed minimum posts the late fee once · payments are
 *             measured from the ledger, never inferred from balance deltas
 *   lesson 5  the limit lives in the ROW · changing it changes the next
 *             authorize decision, and lowering it below current debt is
 *             refused by the same CHECK that enforces spending
 *   lesson 6  the numbers the app shows come from the ledger · the API
 *             serves Credit.limit and the posted-debt fold, not a
 *             hardcoded 1000 and not parallel arithmetic
 *
 * Requires: docker compose up -d   (shards :5434/:5435)
 */
class CreditLessonTest {

    static final long IGOR = 10;
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static com.sun.net.httpserver.HttpServer server;
    static int port;

    @BeforeAll
    static void boot() throws Exception {
        Shards.boot(
                System.getenv().getOrDefault("MINIBANK_SHARD0_URL", "jdbc:postgresql://localhost:5434/minibank"),
                System.getenv().getOrDefault("MINIBANK_SHARD1_URL", "jdbc:postgresql://localhost:5435/minibank"),
                "minibank", "minibank", 5);
        for (Shard s : Shards.all()) s.createSchema();
        server = HttpApi.start(0);
        port = server.getAddress().getPort();
    }

    @org.junit.jupiter.api.AfterAll
    static void down() {
        if (server != null) server.stop(0);
    }

    @BeforeEach
    void freshMoney() throws Exception {
        Fixtures.resetShards();
        Shards.forCustomer(IGOR).createCustomer(IGOR, "igor");
        Shards.forCustomer(IGOR).transferLocal(UUID.randomUUID(), Shard.WORLD, IGOR, eur("500.00"));
        Products.ensureFor(IGOR);
    }

    // ------------------------------------------------------------------
    // backdated facts · the only way to put a purchase in LAST month.
    //
    // The append-only trigger forbids editing history, and that is the
    // point: these helpers do not edit anything, they INSERT entries with
    // an explicit created_at, exactly the shape a relocated archive or a
    // replayed backfill would have. Cached balances are updated in the same
    // commit so the drift audit stays green · a test that breaks invariant
    // #2 to set up a lesson would be teaching with a broken bank.
    // ------------------------------------------------------------------
    static void postAt(long from, long to, BigDecimal amount, Instant at) throws Exception {
        Shard home = Shards.forCustomer(IGOR);
        try (Connection c = home.open()) {
            c.setAutoCommit(false);
            UUID tx = UUID.randomUUID();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO transactions(id, kind, created_at) VALUES (?, 'transfer', ?)")) {
                ps.setObject(1, tx);
                ps.setTimestamp(2, Timestamp.from(at));
                ps.executeUpdate();
            }
            for (long[] leg : new long[][]{{from, -1}, {to, 1}}) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO entries(tx_id, account_id, amount, created_at) VALUES (?,?,?,?)")) {
                    ps.setObject(1, tx);
                    ps.setLong(2, leg[0]);
                    ps.setBigDecimal(3, leg[1] < 0 ? amount.negate() : amount);
                    ps.setTimestamp(4, Timestamp.from(at));
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE accounts SET balance = balance + ? WHERE id = ?")) {
                    ps.setBigDecimal(1, leg[1] < 0 ? amount.negate() : amount);
                    ps.setLong(2, leg[0]);
                    ps.executeUpdate();
                }
            }
            c.commit();
        }
    }

    /** a card purchase stamped inside cycle C · card -> café, backdated */
    static void spendAt(BigDecimal amount, Instant at) throws Exception {
        postAt(IGOR + Products.CARD, Shard.CAFE, amount, at);
    }

    /** a repayment stamped inside the grace window · world -> card, backdated
     *  (the world account so the lesson does not depend on main's balance) */
    static void repayAt(BigDecimal amount, Instant at) throws Exception {
        postAt(Shard.WORLD, IGOR + Products.CARD, amount, at);
    }

    /** the cycle TWO months back · its 21-day grace window is always in the
     *  past, whatever day of the month the suite runs on */
    static Credit.CycleId closedCycle() {
        Credit.CycleId now = Credit.CycleId.of(Instant.now());
        int y = now.year(), m = now.month() - 2;
        if (m < 1) { m += 12; y -= 1; }
        return new Credit.CycleId(y, m);
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 1: a cycle is a month of UTC time · its balances are sums over entries, recomputable forever")
    void lesson1_cycleIsAFold() throws Exception {
        Credit.CycleId c = closedCycle();
        spendAt(eur("100.00"), c.start().plusSeconds(3600));
        spendAt(eur("50.00"), c.end().minusSeconds(3600));
        // a purchase in the NEXT cycle must not touch this statement
        spendAt(eur("30.00"), c.end().plusSeconds(3600));

        Credit.Statement st = Credit.statement(IGOR, c);
        assertEquals(0, eur("0.00").compareTo(st.opening()), "the card started the month clean");
        assertEquals(0, eur("150.00").compareTo(st.statementDebt()), "the cycle owes what the cycle spent");
        assertEquals(2, st.lines().size(), "two purchases belong to this cycle · the third is next month's story");
        assertEquals(c.end().plus(java.time.Duration.ofDays(Credit.GRACE_DAYS)), st.dueAt());

        // an uncaptured HOLD is not debt: authorize now, and the CURRENT
        // cycle's posted debt does not move · holds cancel in the fold
        Credit.CycleId now = Credit.CycleId.of(Instant.now());
        try (Connection conn = Shards.forCustomer(IGOR).open()) {
            BigDecimal before = Credit.postedDebtAt(conn, IGOR, Instant.now());
            Products.authorize(UUID.randomUUID(), IGOR, eur("25.00"));
            BigDecimal after = Credit.postedDebtAt(conn, IGOR, Instant.now());
            assertEquals(0, before.compareTo(after), "a hold reserves money · it is not yet debt");
        }
        assertNotNull(now.key());
        assertEquals(c, Credit.CycleId.parse(c.key()), "the key round-trips");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 2: interest posts ONCE · recomputing a closed cycle cannot double-charge, by primary key")
    void lesson2_interestPostsOnce() throws Exception {
        Credit.CycleId c = closedCycle();
        spendAt(eur("200.00"), c.start().plusSeconds(3600));
        // pay the minimum inside grace so lesson 4's late fee stays out of
        // this lesson's arithmetic: minimum = max(10, 5% of 200) = 10
        repayAt(eur("10.00"), c.end().plusSeconds(3600));

        var home = Shards.forCustomer(IGOR);
        BigDecimal before = home.balance(IGOR + Products.CARD);

        Credit.CloseResult r1 = Credit.closeCycle(IGOR, c);
        // carried = min(statementDebt 200, debtAtDue 190) = 190 · 2% = 3.80
        assertEquals("posted", r1.interest().state());
        assertEquals(0, eur("3.80").compareTo(r1.interest().amount()));
        assertEquals(0, before.subtract(eur("3.80")).compareTo(home.balance(IGOR + Products.CARD)),
                "the charge is a REAL ledger transaction on the card");
        assertEquals(0, eur("3.80").compareTo(home.balance(Credit.BANK_INCOME)),
                "and the other leg landed in the bank's income account · double entry, always");

        // close it again · and again from a 'statement' read: same answer,
        // no second charge. The deterministic id lost the claim race.
        Credit.CloseResult r2 = Credit.closeCycle(IGOR, c);
        assertEquals("posted", r2.interest().state());
        Credit.statement(IGOR, c);   // the lazy path re-runs the close too
        assertEquals(0, before.subtract(eur("3.80")).compareTo(home.balance(IGOR + Products.CARD)),
                "recomputation is free · re-charging is impossible");
        try (Connection conn = home.open()) {
            assertTrue(Ledger.sumZeroViolationsOn(conn).isEmpty(), "the books never blinked");
            assertTrue(Ledger.driftedAccountsOn(conn).isEmpty(), "cache and truth agree");
        }
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 3: full repayment in grace = ZERO interest and NO transaction · grace is a min of two caps")
    void lesson3_graceMeansNoInterest() throws Exception {
        Credit.CycleId c = closedCycle();
        spendAt(eur("300.00"), c.start().plusSeconds(3600));
        repayAt(eur("300.00"), c.end().plusSeconds(7200));   // inside the 21 days

        var home = Shards.forCustomer(IGOR);
        BigDecimal before = home.balance(IGOR + Products.CARD);
        Credit.CloseResult r = Credit.closeCycle(IGOR, c);

        assertEquals("none", r.interest().state(), "nothing carried · nothing charged");
        assertEquals("none", r.lateFee().state(), "full repayment trivially covers the minimum");
        assertEquals(0, before.compareTo(home.balance(IGOR + Products.CARD)),
                "no transaction posted AT ALL · grace is free, not merely cheap");
        assertEquals(0, BigDecimal.ZERO.compareTo(home.balance(Credit.BANK_INCOME)));
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 4: a missed minimum posts the late fee once · payments are measured, not inferred")
    void lesson4_missedMinimumPostsLateFee() throws Exception {
        Credit.CycleId c = closedCycle();
        spendAt(eur("400.00"), c.start().plusSeconds(3600));
        // minimum = max(10, 5% of 400) = 20 · pay only 5, in grace
        repayAt(eur("5.00"), c.end().plusSeconds(3600));

        var home = Shards.forCustomer(IGOR);
        Credit.Statement st = Credit.statement(IGOR, c);
        assertEquals(0, eur("20.00").compareTo(st.minimumDue()));
        assertEquals(0, eur("5.00").compareTo(st.payments()), "the ledger says what was paid · a delta would lie");
        assertEquals("posted", st.lateFee().state());
        assertEquals(0, Credit.LATE_FEE.compareTo(st.lateFee().amount()));

        // once. Not once per read.
        BigDecimal after = home.balance(IGOR + Products.CARD);
        Credit.closeCycle(IGOR, c);
        Credit.statement(IGOR, c);
        assertEquals(0, after.compareTo(home.balance(IGOR + Products.CARD)), "one miss, one fee, forever");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 5: the limit lives in the row · raising it changes the NEXT authorize, lowering below debt is refused")
    void lesson5_limitIsARow() throws Exception {
        var home = Shards.forCustomer(IGOR);
        assertEquals(0, eur("1000").compareTo(Credit.limit(IGOR)), "the shelf's default floor");

        // beyond the default limit: the CHECK says no
        assertInstanceOf(Ledger.InsufficientFunds.class,
                Products.authorize(UUID.randomUUID(), IGOR, eur("1500.00")));

        // raise the limit · ONE row update, and NOTHING in the authorize
        // path changed: same transfer, same CHECK, new floor
        assertInstanceOf(Credit.LimitOk.class, Credit.changeLimit(IGOR, eur("2000.00")));
        UUID auth = UUID.randomUUID();
        assertInstanceOf(Ledger.Ok.class, Products.authorize(auth, IGOR, eur("1500.00")));
        assertInstanceOf(Ledger.Ok.class, Products.capture(auth, IGOR));
        assertEquals(0, eur("-1500.00").compareTo(home.balance(IGOR + Products.CARD)));

        // lowering the limit BELOW current utilization: Postgres re-validates
        // the row CHECK on UPDATE, so the refusal is the schema's, for free
        assertInstanceOf(Credit.DebtAboveLimit.class, Credit.changeLimit(IGOR, eur("1000.00")),
                "a limit the debt already exceeds is refused by the same constraint that enforces spending");
        // and a non-card account cannot have a credit limit at all
        assertInstanceOf(Credit.NotACard.class, Credit.changeLimit(999999, eur("500.00")));

        // the audit trail is an append-only fact table, one row per change
        try (Connection conn = home.open(); var st = conn.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM credit_limit_events WHERE account_id = "
                     + (IGOR + Products.CARD))) {
            rs.next();
            assertEquals(1, rs.getInt(1), "the raise was recorded · the refused lowering was not");
        }
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 6: the app's numbers COME FROM THE LEDGER · the API serves the row and the fold, not a constant")
    void lesson6_uiNumbersAreLedgerNumbers() throws Exception {
        Credit.changeLimit(IGOR, eur("2500.00"));
        UUID auth = UUID.randomUUID();
        Products.authorize(auth, IGOR, eur("400.00"));
        Products.capture(auth, IGOR);

        String current = get("/api/credit/current?customer=" + IGOR);
        assertTrue(current.contains("\"limit\":\"2500\""), "the limit is the row's, not a hardcoded 1000: " + current);
        assertTrue(current.contains("\"postedDebt\":\"400\""), "the debt is the fold over entries: " + current);
        assertTrue(current.contains("\"available\":\"2100\""), "available = limit + card balance · one subtraction, server-side: " + current);
        assertTrue(current.contains("\"isEstimate\":true"), "the interest projection SAYS it is a projection: " + current);

        String portfolio = get("/api/portfolio?customer=" + IGOR);
        assertTrue(portfolio.contains("\"cardLimit\":\"2500\""),
                "the tile's limit travels from the same row · no parallel arithmetic in the browser: " + portfolio);

        // and the statement endpoint answers with the same recomputable facts
        Credit.CycleId c = closedCycle();
        spendAt(eur("120.00"), c.start().plusSeconds(3600));
        String stmt = get("/api/credit/statement?customer=" + IGOR + "&cycle=" + c.key());
        assertTrue(stmt.contains("\"statementDebt\":\"120\""), stmt);
        assertTrue(stmt.contains("\"cycle\":\"" + c.key() + "\""), stmt);
    }

    // ------------------------------------------------------------------
    static String get(String path) throws Exception {
        HttpResponse<String> r = HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, r.statusCode(), r.body());
        return r.body();
    }

    private static BigDecimal eur(String v) {
        return new BigDecimal(v);
    }
}
