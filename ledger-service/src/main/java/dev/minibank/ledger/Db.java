package dev.minibank.ledger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Raw database access. No framework, no pool (yet).
 *
 * DECISION: stage 0 opens a fresh connection per call via DriverManager.
 * That is deliberately naive · opening a TCP connection + Postgres process
 * per query is expensive, and stage 4 will prove it with a load test, then
 * fix it (first with a tiny hand-written pool, then with PgBouncer).
 * You cannot appreciate the cure before feeling the disease.
 */
public final class Db {

    private static final String URL =
            env("MINIBANK_DB_URL", "jdbc:postgresql://localhost:5433/minibank");
    private static final String USER = env("MINIBANK_DB_USER", "minibank");
    private static final String PASSWORD = env("MINIBANK_DB_PASSWORD", "minibank");

    private static volatile MiniPool pool;   // stage 4: null = naive mode

    private Db() {}

    /** Stage 4: flip the whole bank to pooled connections. Nothing else
     *  changes · the pool hands out proxies whose close() means "return",
     *  so every existing try-with-resources keeps working, now for free. */
    public static void usePool(int size) throws SQLException {
        pool = new MiniPool(URL, USER, PASSWORD, size);
    }

    public static MiniPool activePool() { return pool; }

    /** A connection: pooled if the pool is on, a fresh physical one if not.
     *  Caller closes it either way (close = return, when pooled). */
    public static Connection open() throws SQLException {
        MiniPool p = pool;
        if (p != null) return p.borrow(5, java.util.concurrent.TimeUnit.SECONDS);
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    /** Always a REAL physical connection, pool or no pool · the lessons that
     *  measure connection cost need the naive path on demand. */
    public static Connection openPhysical() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }
}
