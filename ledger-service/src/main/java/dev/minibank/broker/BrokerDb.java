package dev.minibank.broker;

import dev.minibank.ledger.Migrate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * THE BROKER'S DATABASE · the fourth "database per service" in this bank,
 * beside the two shards, the directory and the notifications store.
 *
 * It sits on the control-plane Postgres and nothing else may read it. That
 * is not decoration: the moment the ledger could SELECT from positions, the
 * boundary would be a comment rather than a constraint, and the two services
 * would be one service with extra steps.
 *
 * Everything here is deliberately the same shape as Directory and
 * Notifications · same URL derivation, same CREATE DATABASE guard, same
 * Flyway per-database history. A new service should be boring to add.
 */
public final class BrokerDb {

    private static final String DB = "minibank_broker";

    private BrokerDb() {}

    private static String base() {
        return System.getenv().getOrDefault("MINIBANK_DB_URL", "jdbc:postgresql://localhost:5433/minibank");
    }

    private static String url() {
        String b = base();
        return b.substring(0, b.lastIndexOf('/') + 1) + DB;
    }

    public static Connection open() throws SQLException {
        return DriverManager.getConnection(url(), "minibank", "minibank");
    }

    /** Idempotent: create the database if it is not there, then migrate it. */
    public static void createOwnDatabase() throws SQLException {
        try (Connection c = DriverManager.getConnection(base(), "minibank", "minibank");
             var st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + DB + "'")) {
            if (!rs.next()) {
                try (var create = c.createStatement()) {
                    create.execute("CREATE DATABASE " + DB);
                }
            }
        }
        Migrate.run(url(), "minibank", "minibank", "classpath:db/broker");
    }

    // ------------------------------------------------------------------
    // the outbox · identical in spirit to the shards', because the problem
    // is identical: a fill and the event announcing it must share one commit
    // ------------------------------------------------------------------

    public record Event(long id, String topic, String key, String payload) {}

    /** Write an event INSIDE the caller's transaction. Never outside it. */
    public static void appendOutbox(Connection c, String topic, String key, String payload) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO outbox(topic, key, payload) VALUES (?,?,?)")) {
            ps.setString(1, topic);
            ps.setString(2, key);
            ps.setString(3, payload);
            ps.executeUpdate();
        }
    }

    public static java.util.List<Event> pollUnpublished(Connection c, int limit) throws SQLException {
        java.util.List<Event> out = new java.util.ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, topic, key, payload FROM outbox WHERE published_at IS NULL ORDER BY id LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    out.add(new Event(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
            }
        }
        return out;
    }

    /** Marked only AFTER the broker acknowledged. At-least-once, on purpose. */
    public static void markPublished(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE outbox SET published_at = now() WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }
}
