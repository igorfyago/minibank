package dev.minibank.ledger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * The first CONSUMER service: notifications. It represents a different
 * microservice, so it gets its OWN database (database-per-service) · same
 * Postgres container, separate database, no shared tables with the ledger.
 * The ONLY thing connecting the two services is the Kafka topic.
 *
 * THE IDEMPOTENT CONSUMER: the outbox gives at-least-once delivery, so the
 * same event may arrive twice. The cure costs one clause: the event id is
 * the primary key, and INSERT ... ON CONFLICT DO NOTHING makes a duplicate
 * delivery vanish. at-least-once + idempotent consumer = effectively once.
 */
public final class Notifications {

    private static final String DB = "minibank_notifications";

    private Notifications() {}

    private static Connection openOwnDb() throws SQLException {
        String base = System.getenv().getOrDefault("MINIBANK_DB_URL", "jdbc:postgresql://localhost:5433/minibank");
        String url = base.substring(0, base.lastIndexOf('/') + 1) + DB;
        return DriverManager.getConnection(url, "minibank", "minibank");
    }

    /** database-per-service, bootstrapped visibly: create our own database
     *  (Postgres has no CREATE DATABASE IF NOT EXISTS · we check first). */
    public static void createOwnDatabase() throws SQLException {
        try (Connection c = Db.open(); var st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + DB + "'")) {
            if (!rs.next()) {
                try (var create = c.createStatement()) {
                    create.execute("CREATE DATABASE " + DB);
                }
            }
        }
        // Flyway owns the notifications schema · db/notifications/V*.sql
        String base = System.getenv().getOrDefault("MINIBANK_DB_URL", "jdbc:postgresql://localhost:5433/minibank");
        String url = base.substring(0, base.lastIndexOf('/') + 1) + DB;
        Migrate.run(url, "minibank", "minibank", "classpath:db/notifications");
    }

    /** Handle one event. Safe to call any number of times with the same event. */
    public static void handle(String eventKey, String payload) throws SQLException {
        String message = "Payment processed: " + payload;
        try (Connection c = openOwnDb();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO notifications(event_key, message) VALUES (?,?) ON CONFLICT (event_key) DO NOTHING")) {
            ps.setString(1, eventKey);
            ps.setString(2, message);
            ps.executeUpdate();
        }
    }

    /** read-only connection for the API layer. */
    public static Connection openForRead() throws SQLException {
        return openOwnDb();
    }

    public static int count() throws SQLException {
        try (Connection c = openOwnDb(); var st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM notifications")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
