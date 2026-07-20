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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 *             transaction at all · repayment is measured from the ledger
 *   lesson 4  a missed minimum posts the late fee once · payments are
 *             measured from the ledger, never inferred from balance deltas
 *   lesson 5  the limit lives in the ROW · changing it changes the next
 *             authorize decision, and lowering it below current debt is
 *             refused by the same CHECK that enforces spending
 *   lesson 6  the numbers the app shows come from the ledger · the API
 *             serves Credit.limit and the posted-debt fold, not a
 *             hardcoded 1000 and not parallel arithmetic
 *   lesson 7  repaid principal is never re-charged · new spending in grace
 *             belongs to the NEXT statement and cannot keep this one alive
 *   lesson 8  one principal, one charge · two consecutive closes cannot
 *             bill the same money twice
 *   lesson 9  a late close changes no number · a system posting belongs to
 *             the cycle its deterministic id names, not to whenever
 *             somebody happened to read the statement
 *   lesson 10 a frozen card stays frozen across reboots · the boot repair
 *             only touches unaudited rows, and leaves a trace when it does
 *   lesson 11 the boundary instants · the cycle-end instant belongs to
 *             grace, the due instant does not, same convention as the fold
 *   lesson 12 a hold raised and released inside grace is not a repayment
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
        // carried = statementDebt 200 - repaid-in-grace 10 = 190 · 2% = 3.80
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
    @DisplayName("lesson 3: full repayment in grace = ZERO interest and NO transaction · repayment is measured, from the ledger")
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
        assertTrue(current.contains("\"utilization\":\"16.0\""), "utilization is served, not recomputed in the browser: " + current);
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
    @Test
    @DisplayName("lesson 7: repaid principal is never re-charged · new spending in grace belongs to the NEXT statement")
    void lesson7_repaidPrincipalIsNeverRecharged() throws Exception {
        Credit.CycleId c = closedCycle();
        spendAt(eur("300.00"), c.start().plusSeconds(3600));
        // the statement is repaid IN FULL inside grace · and then the card
        // is used again, still inside grace. The old rule (min of statement
        // debt and debt at due) could not tell these apart: the new spending
        // kept debt-at-due high, so the repaid principal was charged anyway.
        repayAt(eur("300.00"), c.end().plus(java.time.Duration.ofDays(1)));
        spendAt(eur("250.00"), c.end().plus(java.time.Duration.ofDays(2)));

        var home = Shards.forCustomer(IGOR);
        Credit.CloseResult r = Credit.closeCycle(IGOR, c);
        assertEquals("none", r.interest().state(),
                "the statement was repaid in full · interest may not resurrect it off the back of new spending");
        assertEquals("none", r.lateFee().state());
        assertEquals(0, BigDecimal.ZERO.compareTo(home.balance(Credit.BANK_INCOME)),
                "no charge posted at all · the 250 is next month's statement, not this month's carry");
        assertEquals(0, eur("300.00").compareTo(Credit.statement(IGOR, c).statementDebt()),
                "and the statement itself never counted the grace-window spending");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 8: one principal, one charge · two consecutive closes cannot bill the same money twice")
    void lesson8_consecutiveClosesCannotDoubleCharge() throws Exception {
        Credit.CycleId cA = closedCycle().previous(), cB = closedCycle();
        spendAt(eur("100.00"), cA.start().plusSeconds(3600));
        // cA repaid in full inside ITS grace, then 80 of new spending in the
        // same window · that 80 is cB's statement, and only cB's
        repayAt(eur("100.00"), cA.end().plus(java.time.Duration.ofDays(1)));
        spendAt(eur("80.00"), cA.end().plus(java.time.Duration.ofDays(2)));
        // cB's minimum (max(10, 5% of 80) = 10) paid inside cB's grace
        repayAt(eur("10.00"), cB.end().plus(java.time.Duration.ofDays(1)));

        var home = Shards.forCustomer(IGOR);
        Credit.CloseResult rA = Credit.closeCycle(IGOR, cA);
        assertEquals("none", rA.interest().state(),
                "close of cA may not charge the 80 · it is not cA's principal. The old rule charged it HERE and then again at cB");
        Credit.CloseResult rB = Credit.closeCycle(IGOR, cB);
        assertEquals("posted", rB.interest().state());
        assertEquals(0, eur("1.40").compareTo(rB.interest().amount()),
                "2% of (80 - 10 repaid) · charged at the close that owns it");
        assertEquals("none", rB.lateFee().state());
        assertEquals(0, eur("1.40").compareTo(home.balance(Credit.BANK_INCOME)),
                "the whole story cost the customer ONE interest charge, not the same principal at two closes");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 9: a late close changes no number · a system posting belongs to the cycle its id names")
    void lesson9_lateCloseChangesNothing() throws Exception {
        Credit.CycleId cA = closedCycle().previous(), cB = closedCycle();
        spendAt(eur("100.00"), cA.start().plusSeconds(3600));
        repayAt(eur("10.00"), cA.end().plus(java.time.Duration.ofDays(1)));   // cA's minimum, on time

        // cA is closed LATE · cB has already ENDED by now. An early close
        // would have posted the interest at cA's due instant, 21 days into
        // cB · so cB's statement must read exactly as if it had.
        Credit.CloseResult rA = Credit.closeCycle(IGOR, cA);
        assertEquals("posted", rA.interest().state());
        assertEquals(0, eur("1.80").compareTo(rA.interest().amount()), "2% of (100 - 10)");

        Credit.Statement st = Credit.statement(IGOR, cB);
        assertEquals(0, eur("91.80").compareTo(st.statementDebt()),
                "cB's statement carries cA's interest · the same number an on-time close would have produced");
        assertEquals("posted", st.interest().state());
        assertEquals(0, eur("1.84").compareTo(st.interest().amount()),
                "2% of 91.80 · interest on interest is collected, not silently forgiven by a slow reader");
        assertTrue(st.lines().stream().anyMatch(l ->
                        l.kind().startsWith("interest") && eur("-1.80").compareTo(l.amount()) == 0),
                "and the interest LINE sits on cB's statement, where the on-time close would have put it: " + st.lines());

        // recomputing must land on the identical numbers · the whole claim
        Credit.Statement again = Credit.statement(IGOR, cB);
        assertEquals(0, st.statementDebt().compareTo(again.statementDebt()));
        assertEquals(0, st.interest().amount().compareTo(again.interest().amount()));
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 10: a frozen card stays frozen across reboots · the repair only touches unaudited rows, and leaves a trace")
    void lesson10_bootRepairRespectsTheFreeze() throws Exception {
        var home = Shards.forCustomer(IGOR);
        // the bank freezes the card: limit 0, recorded in the audit table
        assertInstanceOf(Credit.LimitOk.class, Credit.changeLimit(IGOR, BigDecimal.ZERO));
        assertEquals(0, BigDecimal.ZERO.compareTo(Credit.limit(IGOR)));

        // a restart · createSchema runs at every boot, its repair included
        for (Shard s : Shards.all()) s.createSchema();
        assertEquals(0, BigDecimal.ZERO.compareTo(Credit.limit(IGOR)),
                "a deliberate freeze survives a restart · an audited limit is authoritative forever");
        assertInstanceOf(Ledger.InsufficientFunds.class,
                Products.authorize(UUID.randomUUID(), IGOR, eur("1.00")),
                "and the frozen card still cannot spend a cent");

        // a LEGACY row · floor 0 with no audit history, the shape an insert
        // that forgot the column leaves behind · IS repairable, and the
        // repair must write the audit event itself
        try (Connection conn = home.open(); var st = conn.createStatement()) {
            st.execute("DELETE FROM credit_limit_events WHERE account_id = " + (IGOR + Products.CARD));
            st.execute("UPDATE accounts SET min_balance = 0 WHERE id = " + (IGOR + Products.CARD));
        }
        for (Shard s : Shards.all()) s.createSchema();
        assertEquals(0, eur("1000").compareTo(Credit.limit(IGOR)),
                "an unaudited 0 floor is the fail-closed default, and the repair restores the shelf");
        try (Connection conn = home.open(); var st = conn.createStatement();
             var rs = st.executeQuery("SELECT old_floor, new_floor FROM credit_limit_events WHERE account_id = "
                     + (IGOR + Products.CARD))) {
            assertTrue(rs.next(), "the repair wrote its own audit event · nothing changes a limit without a trace");
            assertEquals(0, BigDecimal.ZERO.compareTo(rs.getBigDecimal(1)));
            assertEquals(0, eur("-1000").compareTo(rs.getBigDecimal(2)));
            assertFalse(rs.next(), "exactly one event · the repair does not fire on rows already at their floor");
        }
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 11a: a payment at the cycle-end instant belongs to grace · same convention as the fold")
    void lesson11a_paymentAtCycleEndIsGrace() throws Exception {
        Credit.CycleId c = closedCycle();
        spendAt(eur("100.00"), c.start().plusSeconds(3600));
        // the fold says entries strictly BEFORE the end belong to the
        // statement · so the end instant itself is the first moment of
        // grace, in the payment measurement exactly as in the debt fold
        repayAt(eur("100.00"), c.end());

        var home = Shards.forCustomer(IGOR);
        Credit.CloseResult r = Credit.closeCycle(IGOR, c);
        assertEquals("none", r.interest().state(), "repaid at the first instant of grace · nothing carried");
        assertEquals("none", r.lateFee().state(), "and the minimum was covered by the same payment");
        assertEquals(0, BigDecimal.ZERO.compareTo(home.balance(Credit.BANK_INCOME)));
        assertEquals(0, eur("100.00").compareTo(Credit.statement(IGOR, c).payments()),
                "the statement's payment figure agrees with the charge that was not posted");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 11b: a payment at the due instant is late · strictly-before, in both measurements")
    void lesson11b_paymentAtDueInstantIsLate() throws Exception {
        Credit.CycleId c = closedCycle();
        spendAt(eur("100.00"), c.start().plusSeconds(3600));
        // one tick too late: the due instant is OUTSIDE grace, exactly as
        // the fold convention says an instant's own entries are outside a
        // balance-before-it. The two windows used to disagree here · a
        // payment at due was invisible to interest and visible to the late
        // fee's measurement.
        repayAt(eur("100.00"), c.due());

        Credit.Statement st = Credit.statement(IGOR, c);
        assertEquals(0, BigDecimal.ZERO.compareTo(st.payments()),
                "the due instant is outside grace in the payment measurement too · one convention, both windows");
        assertEquals("posted", st.interest().state());
        assertEquals(0, eur("2.00").compareTo(st.interest().amount()), "2% of the full 100 · nothing arrived in time");
        assertEquals("posted", st.lateFee().state(), "and the minimum went unpaid inside the window that counts");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 12: a hold raised and released inside grace is not a repayment · the bank returning its own reservation")
    void lesson12_holdReleaseInGraceIsNotARepayment() throws Exception {
        Credit.CycleId c = closedCycle();
        spendAt(eur("300.00"), c.start().plusSeconds(3600));
        // an authorization raised and released inside the grace window ·
        // money leaves card for holds and comes straight back, two
        // transactions that each carry a holds leg, like the real
        // authorize/release pair writes them
        postAt(IGOR + Products.CARD, IGOR + Products.HOLDS, eur("50.00"),
                c.end().plus(java.time.Duration.ofDays(1)));
        postAt(IGOR + Products.HOLDS, IGOR + Products.CARD, eur("50.00"),
                c.end().plus(java.time.Duration.ofDays(2)));

        Credit.Statement st = Credit.statement(IGOR, c);
        assertEquals(0, BigDecimal.ZERO.compareTo(st.payments()),
                "a release is the bank handing back its own reservation · deleting the holds-leg exclusion turns this 50");
        assertEquals("posted", st.interest().state());
        assertEquals(0, eur("6.00").compareTo(st.interest().amount()),
                "2% of the FULL 300 · the released hold repaid nothing");
        assertEquals("posted", st.lateFee().state(),
                "the minimum (15) went unpaid · a release that counted as payment would have covered it");
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
