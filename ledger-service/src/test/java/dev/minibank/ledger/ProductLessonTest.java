package dev.minibank.ledger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PRODUCTS — EVERY PRODUCT IS ACCOUNTS PLUS TRANSFERS.
 *
 *   lesson 1  savings = a second account; "move to savings" is a plain
 *             local ACID transfer between your own accounts
 *   lesson 2  a credit card is a CHECK constraint: the schema, not an
 *             if-statement, enforces the limit
 *   lesson 3  an asset trade is ONE transaction in TWO currencies — and
 *             each currency's ledger sums to zero on its own
 *   lesson 4  a mortgage disbursement leaves your net position unchanged
 *             — borrowing money is honest accounting, not free money
 *
 * Requires: docker compose up -d   (shards :5434/:5435)
 */
class ProductLessonTest {

    static final long IGOR = 10;

    @BeforeAll
    static void boot() throws Exception {
        Shards.boot(
                System.getenv().getOrDefault("MINIBANK_SHARD0_URL", "jdbc:postgresql://localhost:5434/minibank"),
                System.getenv().getOrDefault("MINIBANK_SHARD1_URL", "jdbc:postgresql://localhost:5435/minibank"),
                "minibank", "minibank", 5);
        for (Shard s : Shards.all()) s.createSchema();
    }

    @BeforeEach
    void freshMoney() throws Exception {
        for (Shard s : Shards.all()) {
            try (Connection c = s.open(); var st = c.createStatement()) {
                st.execute("TRUNCATE entries, transactions, outbox, accounts CASCADE");
            }
            s.createSchema();
        }
        Shards.forCustomer(IGOR).createCustomer(IGOR, "igor");
        Shards.forCustomer(IGOR).transferLocal(UUID.randomUUID(), Shard.WORLD, IGOR, eur("500.00"));
        Products.ensureFor(IGOR);
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 1: savings is just another account — moving money there is the stage-1 transfer")
    void lesson1_savingsIsAnAccount() throws Exception {
        var home = Shards.forCustomer(IGOR);
        assertInstanceOf(Ledger.Ok.class,
                home.transferLocal(UUID.randomUUID(), IGOR, IGOR + Products.SAVINGS, eur("150.00")));
        assertEquals(0, eur("350.00").compareTo(home.balance(IGOR)));
        assertEquals(0, eur("150.00").compareTo(home.balance(IGOR + Products.SAVINGS)));
        // and back out — full ACID both ways, deadlock-proof by ordered locks
        assertInstanceOf(Ledger.Ok.class,
                home.transferLocal(UUID.randomUUID(), IGOR + Products.SAVINGS, IGOR, eur("50.00")));
        assertEquals(0, eur("400.00").compareTo(home.balance(IGOR)));
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 2: the credit limit is a CHECK constraint — the schema says no, politely")
    void lesson2_creditIsAConstraint() throws Exception {
        var home = Shards.forCustomer(IGOR);
        long card = IGOR + Products.CARD;
        // spend on credit: the card account goes NEGATIVE — allowed for kind='credit'
        assertInstanceOf(Ledger.Ok.class,
                home.transferLocal(UUID.randomUUID(), card, Shard.CAFE, eur("600.00")));
        assertEquals(0, eur("-600.00").compareTo(home.balance(card)));
        // beyond the -1000 floor the SCHEMA vetoes: 23514 -> InsufficientFunds
        assertInstanceOf(Ledger.InsufficientFunds.class,
                home.transferLocal(UUID.randomUUID(), card, Shard.CAFE, eur("600.00")),
                "the limit lives in the database, not in an if-statement");
        // repay what main can afford: the card heals toward zero — and the
        // repayment itself obeys igor's own funds check (he has 500)
        assertInstanceOf(Ledger.Ok.class,
                home.transferLocal(UUID.randomUUID(), IGOR, card, eur("400.00")));
        assertEquals(0, eur("-200.00").compareTo(home.balance(card)));
        assertEquals(0, eur("100.00").compareTo(home.balance(IGOR)));
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 3: an asset trade is one transaction in two currencies — each ledger balances alone")
    void lesson3_multiCurrencyTrade() throws Exception {
        var home = Shards.forCustomer(IGOR);
        UUID tx = UUID.randomUUID();
        BigDecimal price = eur("90000.00");   // deterministic price for the lesson

        assertInstanceOf(Ledger.Ok.class, Products.trade(tx, IGOR, "btc", true, eur("90.00"), price));
        assertEquals(0, eur("410.00").compareTo(home.balance(IGOR)), "the euros left");
        assertEquals(0, new BigDecimal("0.00100000").compareTo(home.balance(IGOR + Products.BTC)), "the bitcoin arrived");

        // THE invariant, upgraded: sum-zero PER CURRENCY. One tx, four
        // entries, two closed ledgers — and both balance.
        try (Connection c = home.open()) {
            assertEquals(0, Ledger.sumZeroViolationsOn(c).size(), "EUR ledger and BTC ledger each sum to zero");
        }
        // a retry of the same trade is one trade
        assertInstanceOf(Ledger.AlreadyProcessed.class, Products.trade(tx, IGOR, "btc", true, eur("90.00"), price));
        // and selling brings the euros home
        assertInstanceOf(Ledger.Ok.class, Products.trade(UUID.randomUUID(), IGOR, "btc", false, eur("90.00"), price));
        assertEquals(0, eur("500.00").compareTo(home.balance(IGOR)));
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 4: a mortgage moves your cash up and your loan down — net position unchanged")
    void lesson4_mortgageIsHonest() throws Exception {
        var home = Shards.forCustomer(IGOR);
        long loan = IGOR + Products.LOAN;

        assertInstanceOf(Ledger.Ok.class, Products.mortgage(UUID.randomUUID(), IGOR, eur("10000.00")));
        assertEquals(0, eur("10500.00").compareTo(home.balance(IGOR)), "the cash landed");
        assertEquals(0, eur("-10000.00").compareTo(home.balance(loan)), "the debt is on the books");
        // net worth across your accounts: exactly what you started with
        assertEquals(0, eur("500.00").compareTo(home.balance(IGOR).add(home.balance(loan))),
                "borrowing creates no money for YOU — the disbursement sums to zero like everything else");

        // repayments are plain transfers; the loan heals toward zero
        assertInstanceOf(Ledger.Ok.class,
                home.transferLocal(UUID.randomUUID(), IGOR, loan, eur("500.00")));
        assertEquals(0, eur("-9500.00").compareTo(home.balance(loan)));
        try (Connection c = home.open()) {
            assertTrue(Ledger.sumZeroViolationsOn(c).isEmpty(), "the books never blinked");
        }
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 5: card authorization is a HOLD — authorize reserves, capture pays, release undoes")
    void lesson5_authCaptureRelease() throws Exception {
        var home = Shards.forCustomer(IGOR);
        long card = IGOR + Products.CARD, holds = IGOR + Products.HOLDS;

        UUID auth = UUID.randomUUID();
        assertInstanceOf(Ledger.Ok.class, Products.authorize(auth, IGOR, eur("25.00")));
        assertEquals(0, eur("-25.00").compareTo(home.balance(card)), "the card carries the hold");
        assertEquals(0, eur("25.00").compareTo(home.balance(holds)), "the money is reserved, not spent");

        assertInstanceOf(Ledger.Ok.class, Products.capture(auth, IGOR));
        assertEquals(0, BigDecimal.ZERO.compareTo(home.balance(holds)), "the hold drained to the merchant");
        assertEquals(0, eur("-25.00").compareTo(home.balance(card)), "what was held is now owed");
        // the card network redelivers the capture: deterministic id, one payment
        assertInstanceOf(Ledger.AlreadyProcessed.class, Products.capture(auth, IGOR));

        UUID auth2 = UUID.randomUUID();
        assertInstanceOf(Ledger.Ok.class, Products.authorize(auth2, IGOR, eur("30.00")));
        assertInstanceOf(Ledger.Ok.class, Products.release(auth2, IGOR));
        assertEquals(0, eur("-25.00").compareTo(home.balance(card)), "released hold returned to the card");
        // capture AFTER release: the holds account is short -> refused
        assertInstanceOf(Ledger.InsufficientFunds.class, Products.capture(auth2, IGOR),
                "the double-spend dies politely");
        // and the limit counts holds automatically, because the card CARRIES them
        assertInstanceOf(Ledger.InsufficientFunds.class, Products.authorize(UUID.randomUUID(), IGOR, eur("980.00")));
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 6: the ledger is append-only — the DATABASE refuses edits to history")
    void lesson6_appendOnly() throws Exception {
        var home = Shards.forCustomer(IGOR);
        var ex1 = org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> {
            try (Connection c = home.open(); var st = c.createStatement()) {
                st.execute("UPDATE entries SET amount = amount + 1");
            }
        });
        assertTrue(ex1.getMessage().contains("append-only"), "tampering is a database error, not a code review comment");
        var ex2 = org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> {
            try (Connection c = home.open(); var st = c.createStatement()) {
                st.execute("DELETE FROM entries");
            }
        });
        assertTrue(ex2.getMessage().contains("append-only"), "corrections are reversing entries, never edits");
    }

    // ------------------------------------------------------------------
    private static BigDecimal eur(String v) {
        return new BigDecimal(v);
    }
}
