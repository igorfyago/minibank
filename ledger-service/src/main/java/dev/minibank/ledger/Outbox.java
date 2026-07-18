package dev.minibank.ledger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * STAGE 2 · THE TRANSACTIONAL OUTBOX.
 *
 * The problem it solves: after a transfer commits, other services must hear
 * about it (notifications, statements, analytics). The naive approach ·
 * commit to Postgres, THEN publish to Kafka · has a fatal crack: crash
 * between the two and the payment exists but the echo never happens.
 * Publishing FIRST is worse: the echo happens for money that never moved.
 * You cannot atomically commit across two different systems.
 *
 * The trick: don't write to two systems. Write the event INTO THE SAME
 * DATABASE TRANSACTION as the transfer · an `outbox` table. One system,
 * one commit, money and event live or die together. A relay then moves
 * outbox rows to Kafka afterwards, at its own pace.
 *
 * Delivery guarantee this creates: AT-LEAST-ONCE. The relay might publish
 * a row and crash before marking it published · on restart it publishes
 * again. Duplicates are possible, loss is not. That is exactly the right
 * trade for money, and it is why every consumer must be idempotent.
 */
public final class Outbox {

    /** One event, one row. Written inside the business transaction. */
    public record Event(long id, String topic, String key, String payload) {}

    private Outbox() {}

    public static void createTable() throws SQLException {
        try (Connection c = Db.open()) {
            createTableOn(c);
        }
    }

    public static void createTableOn(Connection c) throws SQLException {
        try (var st = c.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS outbox (
                    id           BIGSERIAL PRIMARY KEY,
                    topic        TEXT NOT NULL,
                    key          TEXT NOT NULL,
                    payload      TEXT NOT NULL,
                    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
                    published_at TIMESTAMPTZ
                )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_outbox_unpublished ON outbox(id) WHERE published_at IS NULL");
        }
    }

    /** Called INSIDE an open business transaction · same commit, same fate. */
    static void append(Connection conn, String topic, String key, String payload) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO outbox(topic, key, payload) VALUES (?,?,?)")) {
            ps.setString(1, topic);
            ps.setString(2, key);
            ps.setString(3, payload);
            ps.executeUpdate();
        }
    }

    /** The relay's read: oldest unpublished events first. */
    public static List<Event> pollUnpublished(int limit) throws SQLException {
        try (Connection c = Db.open()) {
            return pollUnpublishedOn(c, limit);
        }
    }

    public static List<Event> pollUnpublishedOn(Connection c, int limit) throws SQLException {
        List<Event> events = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                     "SELECT id, topic, key, payload FROM outbox WHERE published_at IS NULL ORDER BY id LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    events.add(new Event(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
                }
            }
        }
        return events;
    }

    /** Marked AFTER the broker confirmed. Crash before this line = the event
     *  will be sent again = at-least-once. Never mark before sending. */
    public static void markPublished(long id) throws SQLException {
        try (Connection c = Db.open()) {
            markPublishedOn(c, id, Instant.now());
        }
    }

    /**
     * The instant is the caller's, and that is the whole point.
     *
     * This used to write now(), which is the time of the UPDATE: one database
     * round trip AFTER the broker acked, by which time the consumer in another
     * region may already have received the message, applied it and finished. A
     * trace built from that reads "uk received a message that had not been sent
     * yet", because the label says acked and the number meant marked.
     *
     * Two smaller traps go with it. Postgres now() is TRANSACTION time, not
     * statement time, so a batch marked inside one transaction would collapse
     * onto a single instant and the trace would sort arbitrary ties. And the
     * timestamp would be the database's clock rather than the clock that
     * observed the ack. Passing the instant in settles all three.
     */
    public static void markPublishedOn(Connection c, long id, Instant ackedAt) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE outbox SET published_at = ? WHERE id = ?")) {
            ps.setTimestamp(1, java.sql.Timestamp.from(ackedAt));
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }
}
