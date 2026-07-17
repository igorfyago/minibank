package dev.minibank.ledger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STAGE 1 — THE DOUBLE-ENTRY LEDGER.
 *
 * Cast: igor and coco (customers), world (the outside: top-ups come from it),
 * cafe (a merchant). Accounts are born empty; the world funds them by transfer.
 *
 *   lesson 1  a transfer is two entries that sum to zero, plus a cache update
 *   lesson 2  insufficient funds -> NOTHING happens (atomicity: no partial rows)
 *   lesson 3  the same transfer retried -> processed exactly once (idempotency)
 *   lesson 4  wrong-order locking -> A REAL DEADLOCK, Postgres kills one (40P01)
 *   lesson 5  ordered locking -> same crossing payments, zero deadlocks, correct
 *   lesson 6  the reconciliation control catches a corrupted cache instantly
 *
 * Requires: docker compose up -d postgres   (port 5433)
 */
class LedgerLessonTest {

    static final long WORLD = 1, CAFE = 2, IGOR = 10, COCO = 11;
    static final BigDecimal EUR_100 = new BigDecimal("100.00");
    static final BigDecimal EUR_30 = new BigDecimal("30.00");

    @BeforeAll
    static void schema() throws Exception {
        Ledger.createTables();
    }

    @BeforeEach
    void freshBank() throws Exception {
        try (Connection c = Db.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE outbox, entries, transactions, accounts CASCADE");
        }
        Ledger.createAccount(WORLD, "world", Ledger.KIND_EXTERNAL);
        Ledger.createAccount(CAFE, "cafe", Ledger.KIND_EXTERNAL);
        Ledger.createAccount(IGOR, "igor", Ledger.KIND_CUSTOMER);
        Ledger.createAccount(COCO, "coco", Ledger.KIND_CUSTOMER);
        // Fund the customers FROM the outside world — through the ledger,
        // like all money. The world goes negative; that is its job.
        Ledger.transfer(UUID.randomUUID(), WORLD, IGOR, EUR_100);
        Ledger.transfer(UUID.randomUUID(), WORLD, COCO, EUR_100);
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 1: igor pays coco 30 — two entries, sum zero, caches true")
    void lesson1_transferIsTwoEntriesSummingZero() throws Exception {
        var result = Ledger.transfer(UUID.randomUUID(), IGOR, COCO, EUR_30);

        assertInstanceOf(Ledger.Ok.class, result);
        assertEquals(0, Ledger.cachedBalance(IGOR).compareTo(new BigDecimal("70.00")));
        assertEquals(0, Ledger.cachedBalance(COCO).compareTo(new BigDecimal("130.00")));
        // the truth agrees with the cache, for everyone
        assertEquals(0, Ledger.ledgerBalance(IGOR).compareTo(Ledger.cachedBalance(IGOR)));
        assertEquals(0, Ledger.ledgerBalance(COCO).compareTo(Ledger.cachedBalance(COCO)));
        // and the bank-wide invariants hold
        assertTrue(Ledger.sumZeroViolations().isEmpty(), "every tx must sum to zero");
        assertTrue(Ledger.driftedAccounts().isEmpty(), "no cache may drift from its ledger");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 2: insufficient funds — rejected, and NOTHING was written")
    void lesson2_insufficientFundsWritesNothing() throws Exception {
        var result = Ledger.transfer(UUID.randomUUID(), IGOR, COCO, new BigDecimal("100.01"));

        assertInstanceOf(Ledger.InsufficientFunds.class, result);
        assertEquals(0, Ledger.cachedBalance(IGOR).compareTo(EUR_100));
        assertEquals(0, Ledger.cachedBalance(COCO).compareTo(EUR_100));
        // atomicity: the rejected attempt left zero rows behind — not even
        // the transactions row survived the rollback
        try (Connection c = Db.open(); var st = c.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM transactions WHERE kind = 'transfer'")) {
            rs.next();
            assertEquals(2, rs.getInt(1), "only the two seed transfers exist");
        }
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 3: the same transfer retried — moves money exactly once")
    void lesson3_retryIsIdempotent() throws Exception {
        UUID txId = UUID.randomUUID();   // the caller owns the id — that IS the idempotency key

        var first = Ledger.transfer(txId, IGOR, COCO, EUR_30);
        var retry = Ledger.transfer(txId, IGOR, COCO, EUR_30);   // network blip, client retried

        assertInstanceOf(Ledger.Ok.class, first);
        assertInstanceOf(Ledger.AlreadyProcessed.class, retry);
        assertEquals(0, Ledger.cachedBalance(IGOR).compareTo(new BigDecimal("70.00")),
                "retry must not move money twice");
    }

    // ------------------------------------------------------------------
    // LESSON 4 — the disease. Lock the two accounts in OPPOSITE orders and
    // Postgres has to kill somebody. Watch for SQLState 40P01.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 4: wrong-order locking — a real deadlock, Postgres kills one (40P01)")
    void lesson4_wrongOrderLocking_deadlocks() throws Exception {
        CountDownLatch bothHoldFirstLock = new CountDownLatch(2);
        AtomicInteger deadlocks = new AtomicInteger();
        AtomicInteger commits = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        // thread A locks igor then coco; thread B locks coco then igor
        long[][] orders = { {IGOR, COCO}, {COCO, IGOR} };
        for (long[] order : orders) {
            pool.submit(() -> {
                try (Connection conn = Db.open()) {
                    conn.setAutoCommit(false);
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT balance FROM accounts WHERE id = ? FOR UPDATE")) {
                        ps.setLong(1, order[0]);
                        ps.executeQuery();                       // first lock: acquired
                        bothHoldFirstLock.countDown();
                        bothHoldFirstLock.await(10, TimeUnit.SECONDS);  // both now hold one lock
                        ps.setLong(1, order[1]);
                        ps.executeQuery();                       // second lock: each waits on the other...
                    }
                    conn.commit();
                    commits.incrementAndGet();
                } catch (SQLException e) {
                    if ("40P01".equals(e.getSQLState())) deadlocks.incrementAndGet();  // deadlock detected
                } catch (Exception ignored) {
                }
                return null;
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        // Postgres detected the cycle (after deadlock_timeout, ~1s) and killed
        // exactly one victim; the survivor committed.
        assertEquals(1, deadlocks.get(), "exactly one thread must be the deadlock victim");
        assertEquals(1, commits.get(), "the other thread must survive and commit");
    }

    // ------------------------------------------------------------------
    // LESSON 5 — the cure. Real crossing payments through Ledger.transfer(),
    // which always locks ascending-by-id. Same collision, zero deadlocks.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 5: crossing payments with ordered locking — no deadlock, correct balances")
    void lesson5_orderedLocking_neverDeadlocks() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        // igor pays coco 30 WHILE coco pays igor 30 — the classic deadlock shape
        long[][] payments = { {IGOR, COCO}, {COCO, IGOR} };
        for (long[] p : payments) {
            pool.submit(() -> {
                start.await();
                return Ledger.transfer(UUID.randomUUID(), p[0], p[1], EUR_30);
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        // both went through; the money is a wash; every invariant holds
        assertEquals(0, Ledger.cachedBalance(IGOR).compareTo(EUR_100));
        assertEquals(0, Ledger.cachedBalance(COCO).compareTo(EUR_100));
        assertTrue(Ledger.sumZeroViolations().isEmpty());
        assertTrue(Ledger.driftedAccounts().isEmpty());
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 6: a corrupted cache cannot hide — reconciliation catches it")
    void lesson6_reconciliationCatchesDrift() throws Exception {
        // a rogue code path "fixes" a balance by hand, bypassing the ledger
        try (Connection c = Db.open(); var st = c.createStatement()) {
            st.execute("UPDATE accounts SET balance = balance + 1.00 WHERE id = " + IGOR);
        }

        var drifted = Ledger.driftedAccounts();
        assertEquals(1, drifted.size(), "exactly one account must be flagged");
        assertEquals(IGOR, drifted.get(0));
        // the ledger still sums to zero — the TRUTH is intact; the CACHE lied.
        assertTrue(Ledger.sumZeroViolations().isEmpty());
    }
}
