package dev.minibank.ledger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * WHERE A SAGA STEP GOES WHEN IT CANNOT BE COMPLETED · the difference between
 * a failure and a disappearance.
 *
 * Both saga consumers used to catch Exception, print it, and carry on. Neither
 * set enable.auto.commit, so it defaulted to true and the offset advanced on
 * the next poll regardless · a compensation that threw, a transient
 * SQLException, a shard briefly unreachable, all identical: the step was
 * dropped, nothing retried it, and the only trace was a line on stderr that
 * scrolled away. The saga's whole claim is that every fill ends in money or in
 * a compensation, and a silently discarded step is the one outcome that claim
 * does not cover.
 *
 * So a step that will not go through lands HERE, in the consumer's own
 * database, where it can be counted, listed and re-driven. This is the same
 * argument the outbox makes in the other direction: an event that matters must
 * survive the process that was handling it.
 *
 * WHY IT DOES NOT WEDGE THE PARTITION INSTEAD. Refusing to commit the offset
 * is a real option and it is the one that guarantees no loss · but a single
 * poison record then blocks every other customer's settlements behind it,
 * turning one stuck order into an outage. Retry a bounded number of times,
 * record what would not go through, and let the queue move: the stuck saga is
 * still visible, both here and · because the order stays at status 'filled' ·
 * as a divergence in {@link dev.minibank.broker.Reconciliation} once the
 * in-flight grace expires. Two independent witnesses to the same stall.
 *
 * KEYED, NOT APPENDED. A redelivered poison record must not grow a new row
 * every time Kafka hands it back, or the count that is supposed to mean "how
 * many steps are stuck" becomes "how many times we noticed", which is a
 * different and much less useful number.
 */
public final class DeadLetter {

    private DeadLetter() {}

    /** One step that would not complete. */
    public record Entry(String consumer, String eventKey, String error, int attempts) {
        @Override
        public String toString() {
            return consumer + " · " + eventKey + " · after " + attempts + " attempt(s): " + error;
        }
    }

    /**
     * Idempotent DDL, mirroring the Flyway migration in db/broker and
     * db/shard, because a test builds its schema in Java and must get the
     * same tables Flyway builds in production.
     */
    public static void createTableOn(Connection c) throws SQLException {
        try (var st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS saga_dead_letters (
                        id         BIGSERIAL PRIMARY KEY,
                        consumer   TEXT NOT NULL,
                        event_key  TEXT NOT NULL,
                        topic      TEXT NOT NULL,
                        payload    TEXT NOT NULL,
                        error      TEXT NOT NULL,
                        attempts   INT  NOT NULL,
                        first_seen TIMESTAMPTZ NOT NULL DEFAULT now(),
                        last_seen  TIMESTAMPTZ NOT NULL DEFAULT now(),
                        UNIQUE (consumer, event_key)
                    )""");
        }
    }

    /**
     * Record a step that would not complete after every retry.
     *
     * The upsert keeps one row per (consumer, event) and counts the attempts
     * onto it, so the table answers "what is stuck" rather than "what have we
     * seen fail", and a redelivery loop cannot inflate it.
     */
    public static void record(Connection c, String consumer, String eventKey, String topic,
                              String payload, String error, int attempts) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO saga_dead_letters(consumer, event_key, topic, payload, error, attempts)
                VALUES (?,?,?,?,?,?)
                ON CONFLICT (consumer, event_key) DO UPDATE
                   SET attempts  = saga_dead_letters.attempts + EXCLUDED.attempts,
                       error     = EXCLUDED.error,
                       last_seen = now()""")) {
            ps.setString(1, consumer);
            ps.setString(2, eventKey);
            ps.setString(3, topic);
            ps.setString(4, payload);
            ps.setString(5, error);
            ps.setInt(6, attempts);
            ps.executeUpdate();
        }
    }

    /** Everything currently stuck, oldest first · the audit's third list. */
    public static List<Entry> all(Connection c) throws SQLException {
        List<Entry> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT consumer, event_key, error, attempts FROM saga_dead_letters ORDER BY first_seen");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next())
                out.add(new Entry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)));
        }
        return out;
    }

    /** Clear one entry · what a successful re-drive does. */
    public static void clear(Connection c, String consumer, String eventKey) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM saga_dead_letters WHERE consumer = ? AND event_key = ?")) {
            ps.setString(1, consumer);
            ps.setString(2, eventKey);
            ps.executeUpdate();
        }
    }
}
