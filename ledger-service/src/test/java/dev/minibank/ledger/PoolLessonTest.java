package dev.minibank.ledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STAGE 4 — A DATABASE DROWNS IN CONNECTIONS BEFORE IT DROWNS IN DATA.
 *
 *   lesson 1  connection-per-query vs pooled — measured, not asserted by vibes
 *   lesson 2  100 virtual threads share 5 connections — all succeed, none melt
 *   lesson 3  pool exhaustion = backpressure: the N+1th borrower WAITS (or times out)
 *   lesson 4  returned connections are CLEAN: no transaction state leaks between borrowers
 *   lesson 5  the same workload through PgBouncer (:6432) — the ops-grade pool
 *
 * Requires: docker compose up -d   (postgres 5433, pgbouncer 6432)
 */
class PoolLessonTest {

    private static final int QUERIES = 200;

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 1: connection-per-query pays a tax on EVERY query — the pool pays it once")
    void lesson1_measureTheTax() throws Exception {
        // warm-up (JIT, driver init) so the measurement is honest
        try (Connection c = Db.openPhysical(); var st = c.createStatement()) { st.execute("SELECT 1"); }

        long naiveMs = timed(() -> {
            for (int i = 0; i < QUERIES; i++) {
                try (Connection c = Db.openPhysical();                 // TCP + auth + process, EVERY time
                     PreparedStatement ps = c.prepareStatement("SELECT 1");
                     ResultSet rs = ps.executeQuery()) {
                    rs.next();
                }
            }
        });

        try (MiniPool pool = new MiniPool(url(), "minibank", "minibank", 5)) {
            long pooledMs = timed(() -> {
                for (int i = 0; i < QUERIES; i++) {
                    try (Connection c = pool.borrow(5, TimeUnit.SECONDS);   // handed an OPEN connection
                         PreparedStatement ps = c.prepareStatement("SELECT 1");
                         ResultSet rs = ps.executeQuery()) {
                        rs.next();
                    }
                }
            });

            System.out.printf("lesson 1: %d queries — connection-per-query %d ms, pooled %d ms (%.0fx)%n",
                    QUERIES, naiveMs, pooledMs, (double) naiveMs / Math.max(pooledMs, 1));
            assertTrue(naiveMs > pooledMs * 2,
                    "pooling must be at least 2x faster (measured naive=" + naiveMs + "ms pooled=" + pooledMs + "ms)");
        }
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 2: 100 virtual threads share 5 connections — everyone gets served")
    void lesson2_manyThreadsFewConnections() throws Exception {
        try (MiniPool pool = new MiniPool(url(), "minibank", "minibank", 5)) {
            AtomicInteger completed = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(100);
            for (int i = 0; i < 100; i++) {
                Thread.startVirtualThread(() -> {
                    try (Connection c = pool.borrow(10, TimeUnit.SECONDS);
                         PreparedStatement ps = c.prepareStatement("SELECT pg_sleep(0.01)")) {
                        ps.execute();
                        completed.incrementAndGet();
                    } catch (SQLException e) {
                        // counted by its absence
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(60, TimeUnit.SECONDS));
            assertEquals(100, completed.get(), "5 connections served 100 borrowers by taking turns");
            assertEquals(5, pool.idleCount(), "and every connection came home");
        }
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 3: pool exhausted — the next borrower waits, then times out. That IS backpressure")
    void lesson3_exhaustionIsBackpressure() throws Exception {
        try (MiniPool pool = new MiniPool(url(), "minibank", "minibank", 2)) {
            Connection a = pool.borrow(1, TimeUnit.SECONDS);
            Connection b = pool.borrow(1, TimeUnit.SECONDS);   // pool now empty

            long start = System.nanoTime();
            SQLException e = assertThrows(SQLException.class, () -> pool.borrow(500, TimeUnit.MILLISECONDS));
            long waitedMs = (System.nanoTime() - start) / 1_000_000;

            assertTrue(e.getMessage().contains("exhausted"));
            assertTrue(waitedMs >= 400, "it genuinely waited (backpressure), it didn't fail fast");

            a.close();                                          // give one back...
            try (Connection c = pool.borrow(500, TimeUnit.MILLISECONDS)) {
                assertTrue(c.isValid(1));                       // ...and the queue moves again
            }
            b.close();
        }
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 4: a borrower leaves a transaction open — the pool cleans it; state never leaks")
    void lesson4_noStateLeaksBetweenBorrowers() throws Exception {
        try (MiniPool pool = new MiniPool(url(), "minibank", "minibank", 1)) {   // ONE connection: reuse guaranteed
            try (Connection dirty = pool.borrow(1, TimeUnit.SECONDS)) {
                dirty.setAutoCommit(false);                     // opens a transaction...
                try (var st = dirty.createStatement()) {
                    st.execute("SELECT 1");
                }
                // ...and "forgets" to commit. close() returns it to the pool.
            }
            try (Connection next = pool.borrow(1, TimeUnit.SECONDS)) {
                assertTrue(next.getAutoCommit(),
                        "the pool rolled back and reset autocommit — the next borrower gets a clean connection");
            }
        }
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 5: the same workload through PgBouncer — the pool as infrastructure")
    void lesson5_throughPgBouncer() throws Exception {
        // PgBouncer in TRANSACTION pooling mode: thousands of clients share a
        // handful of real connections, assigned per-transaction. The JDBC
        // gotcha: server-side prepared statements break in this mode, so
        // prepareThreshold=0 keeps the driver on simple protocol. Real-world
        // scar tissue, one URL parameter.
        String url = "jdbc:postgresql://localhost:6432/minibank?prepareThreshold=0";
        for (int i = 0; i < 25; i++) {
            try (Connection c = DriverManager.getConnection(url, "minibank", "minibank");
                 PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM accounts");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
            }
        }
        // it works — and behind the curtain PgBouncer held far fewer real
        // connections than we opened. Same trick as MiniPool, ops-grade.
    }

    // ------------------------------------------------------------------
    private static long timed(ThrowingRunnable r) throws Exception {
        long start = System.nanoTime();
        r.run();
        return (System.nanoTime() - start) / 1_000_000;
    }

    @FunctionalInterface
    interface ThrowingRunnable { void run() throws Exception; }

    private static String url() {
        return System.getenv().getOrDefault("MINIBANK_DB_URL", "jdbc:postgresql://localhost:5433/minibank");
    }
}
