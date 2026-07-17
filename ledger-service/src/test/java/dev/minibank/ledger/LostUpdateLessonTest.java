package dev.minibank.ledger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STAGE 0 — THE LOST UPDATE, WATCHED LIVE, THEN KILLED THREE WAYS.
 * Raw Java 21 + raw JDBC. No framework. Every connection, transaction and
 * commit below is explicit, because understanding them IS the lesson.
 *
 * The story: one joint account holds 100.00. Two card payments of 30.00
 * arrive at the same moment. Correct final balance: 40.00.
 *
 *   lesson 1  naive read-compute-write  → 30.00 VANISHES (balance ends 70.00)
 *   lesson 2  SELECT ... FOR UPDATE     → 40.00 (pessimistic: second writer WAITS)
 *   lesson 3  version column + retry    → 40.00 (optimistic: conflict DETECTED, retried)
 *   lesson 4  one atomic UPDATE         → 40.00 (read+check+write become ONE step)
 *
 * Forever rule: read-then-write is two steps; two steps interleave.
 * Make others wait, detect-and-retry, or make it one step.
 *
 * KEY DETAIL you must notice: each racing thread opens ITS OWN connection.
 * Two threads sharing one connection wouldn't race — they'd queue on the
 * driver. Real systems race precisely because every request has its own
 * connection. (This is also why "just share a connection" is not a fix.)
 *
 * Requires: docker compose up -d postgres   (port 5433)
 */
class LostUpdateLessonTest {

    private static final long ACCOUNT = 1L;
    private static final BigDecimal SPEND = new BigDecimal("30.00");

    @BeforeAll
    static void createTable() throws Exception {
        // No framework runs our DDL for us: we do it, visibly.
        try (Connection c = Db.open(); var st = c.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS accounts (
                    id      BIGINT PRIMARY KEY,
                    owner   TEXT   NOT NULL,
                    balance NUMERIC(12,2) NOT NULL CHECK (balance >= 0),
                    version BIGINT NOT NULL DEFAULT 0
                )""");
        }
    }

    @BeforeEach
    void freshAccount() throws Exception {
        try (Connection c = Db.open(); var st = c.createStatement()) {
            st.execute("DELETE FROM accounts");
            st.execute("INSERT INTO accounts(id, owner, balance, version) VALUES (1, 'joint', 100.00, 0)");
        }
    }

    private BigDecimal balance() throws Exception {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement("SELECT balance FROM accounts WHERE id = ?")) {
            ps.setLong(1, ACCOUNT);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    /** Two payments start at the same instant, each on its own connection. */
    private void race(Payment payment) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                start.await();
                try (Connection conn = Db.open()) {   // own connection per thread!
                    payment.run(conn);
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "payments did not finish");
    }

    @FunctionalInterface
    interface Payment {
        void run(Connection conn) throws Exception;
    }

    // ------------------------------------------------------------------
    // LESSON 1 — the bug. Both threads read 100 before either writes 70.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 1: naive read-compute-write LOSES 30 — balance ends 70, not 40")
    void lesson1_naiveReadModifyWrite_losesMoney() throws Exception {
        CountDownLatch bothHaveRead = new CountDownLatch(2);

        race(conn -> {
            // step 1: READ the balance
            BigDecimal current;
            try (PreparedStatement ps = conn.prepareStatement("SELECT balance FROM accounts WHERE id = ?")) {
                ps.setLong(1, ACCOUNT);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    current = rs.getBigDecimal(1);
                }
            }

            // force the fatal interleaving: hold here until the OTHER thread has also read
            bothHaveRead.countDown();
            bothHaveRead.await(10, TimeUnit.SECONDS);

            // step 2: COMPUTE in app memory (invisible to the DB)
            BigDecimal stale = current.subtract(SPEND);

            // step 3: WRITE the stale result
            try (PreparedStatement ps = conn.prepareStatement("UPDATE accounts SET balance = ? WHERE id = ?")) {
                ps.setBigDecimal(1, stale);
                ps.setLong(2, ACCOUNT);
                ps.executeUpdate();
            }
        });

        // Two 30.00 payments happened; the balance dropped by only 30.00.
        // No error, no crash. 30.00 is simply gone. THIS is the lost update.
        assertEquals(0, balance().compareTo(new BigDecimal("70.00")));
    }

    // ------------------------------------------------------------------
    // LESSON 2 — pessimistic. Lock the row BEFORE reading; second waits.
    // Note autoCommit=false: FOR UPDATE only holds the lock inside an open
    // transaction. Frameworks hide this begin/commit; here you own it.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 2: SELECT ... FOR UPDATE — second writer waits, balance ends 40")
    void lesson2_selectForUpdate_isCorrect() throws Exception {
        race(conn -> {
            conn.setAutoCommit(false);            // BEGIN
            try {
                BigDecimal current;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT balance FROM accounts WHERE id = ? FOR UPDATE")) {
                    ps.setLong(1, ACCOUNT);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        current = rs.getBigDecimal(1);   // second thread BLOCKS here...
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE accounts SET balance = ? WHERE id = ?")) {
                    ps.setBigDecimal(1, current.subtract(SPEND));
                    ps.setLong(2, ACCOUNT);
                    ps.executeUpdate();
                }
                conn.commit();                    // ...until THIS commit releases the lock
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        });

        assertEquals(0, balance().compareTo(new BigDecimal("40.00")));
    }

    // ------------------------------------------------------------------
    // LESSON 3 — optimistic. Write only if version unchanged; retry if beaten.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 3: version column + retry — conflict detected, balance ends 40")
    void lesson3_optimisticVersion_isCorrect() throws Exception {
        race(conn -> {
            for (int attempt = 0; attempt < 10; attempt++) {
                BigDecimal current;
                long version;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT balance, version FROM accounts WHERE id = ?")) {
                    ps.setLong(1, ACCOUNT);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        current = rs.getBigDecimal(1);
                        version = rs.getLong(2);
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE accounts SET balance = ?, version = version + 1 " +
                        "WHERE id = ? AND version = ?")) {
                    ps.setBigDecimal(1, current.subtract(SPEND));
                    ps.setLong(2, ACCOUNT);
                    ps.setLong(3, version);
                    if (ps.executeUpdate() == 1) return;  // our view was still true
                    // 0 rows: someone beat us — loop, re-read, try again
                }
            }
            throw new IllegalStateException("gave up after 10 attempts");
        });

        assertEquals(0, balance().compareTo(new BigDecimal("40.00")));
    }

    // ------------------------------------------------------------------
    // LESSON 4 — atomic. Read+check+write as ONE statement inside the DB.
    // Two steps can interleave; one step cannot.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 4: one atomic UPDATE — no separate read exists, balance ends 40")
    void lesson4_atomicUpdate_isCorrect() throws Exception {
        race(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE accounts SET balance = balance - ? WHERE id = ? AND balance >= ?")) {
                ps.setBigDecimal(1, SPEND);
                ps.setLong(2, ACCOUNT);
                ps.setBigDecimal(3, SPEND);
                if (ps.executeUpdate() == 0) throw new IllegalStateException("insufficient funds");
            }
        });

        assertEquals(0, balance().compareTo(new BigDecimal("40.00")));
    }
}
