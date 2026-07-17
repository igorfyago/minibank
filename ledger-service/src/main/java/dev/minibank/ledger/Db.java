package dev.minibank.ledger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Raw database access. No framework, no pool (yet).
 *
 * DECISION: stage 0 opens a fresh connection per call via DriverManager.
 * That is deliberately naive — opening a TCP connection + Postgres process
 * per query is expensive, and stage 4 will prove it with a load test, then
 * fix it (first with a tiny hand-written pool, then with PgBouncer).
 * You cannot appreciate the cure before feeling the disease.
 */
public final class Db {

    private static final String URL =
            env("MINIBANK_DB_URL", "jdbc:postgresql://localhost:5433/minibank");
    private static final String USER = env("MINIBANK_DB_USER", "minibank");
    private static final String PASSWORD = env("MINIBANK_DB_PASSWORD", "minibank");

    private Db() {}

    /** One new physical connection. Caller closes it (use try-with-resources). */
    public static Connection open() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }
}
