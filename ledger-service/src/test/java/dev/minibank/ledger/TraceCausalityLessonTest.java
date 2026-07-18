package dev.minibank.ledger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A TRACE MUST NOT RECORD AN EFFECT BEFORE ITS CAUSE.
 *
 * The bug: a cross-region transfer produced this trace, on the live site.
 *
 *   depart     21:17:43.765   money leaves eu
 *   arrive     21:17:44.256   money lands in uk
 *   published  21:17:44.275   "relay -> Kafka (broker acked, then marked)"
 *   notify     21:17:44.285
 *
 * The applier consumed the message 19 milliseconds BEFORE the relay is recorded
 * as having handed it to Kafka. Read literally, uk received a message that had
 * not been sent yet.
 *
 * Nothing was actually out of order. The RECORD was wrong. publishPending does:
 *
 *     producer.send(record).get();       // the broker has it. Consumers can see it NOW.
 *     Outbox.markPublishedOn(c, id);     // UPDATE ... published_at = now()
 *
 * so published_at was the time of the MARK, one database round trip after the
 * ack, while the applier was already awake and working. The label said "broker
 * acked" and the timestamp meant "row updated", and the gap between those two
 * readings is exactly wide enough for the consumer to finish.
 *
 * This is not cosmetic. The X-ray animates that timeline as pulses travelling
 * along the map, so a timestamp that disagrees with causality is drawn as money
 * arriving in another region before it was ever sent. The picture teaches the
 * wrong thing, which for this repo is the only failure that really matters.
 *
 * Requires: docker compose up -d --wait
 */
class TraceCausalityLessonTest {

    @BeforeAll
    static void setup() throws Exception {
        Ledger.createTables();
        Outbox.createTable();
    }

    @BeforeEach
    void clean() throws Exception {
        try (Connection c = Db.open(); var st = c.createStatement()) {
            st.execute("DELETE FROM outbox");
        }
    }

    @Test
    @DisplayName("lesson 1 · published_at records when the BROKER ACKED, not when the row was later marked")
    void publishedAtIsTheAckNotTheMark() throws Exception {
        try (Connection c = Db.open()) {
            Outbox.append(c, "payments", "k1", "{\"type\":\"probe\"}");
        }
        long id = idOf("k1");

        // The ack happens here. Everything after this instant is bookkeeping,
        // and a consumer somewhere else may already be acting on the message.
        Instant ack = Instant.now();

        // Stand in for a slow mark: a busy pool, a GC pause, a database that
        // took its time. The real gap is a millisecond or two; this makes the
        // difference impossible to miss and impossible to pass by luck.
        Thread.sleep(400);

        try (Connection c = Db.open()) {
            Outbox.markPublishedOn(c, id, ack);
        }

        Instant recorded = publishedAt(id);
        long driftMs = Math.abs(java.time.Duration.between(ack, recorded).toMillis());
        assertTrue(driftMs < 100,
                "published_at must be the ack instant, not the mark instant. Recorded "
                        + recorded + " for an ack at " + ack + ", drift " + driftMs + "ms. "
                        + "A trace built from the mark shows the message being sent after it "
                        + "was received.");
    }

    @Test
    @DisplayName("lesson 2 · every row in one batch keeps its OWN ack time, not the batch's")
    void eachRowKeepsItsOwnAckInstant() throws Exception {
        try (Connection c = Db.open()) {
            Outbox.append(c, "payments", "k-a", "{\"n\":1}");
            Outbox.append(c, "payments", "k-b", "{\"n\":2}");
        }
        long a = idOf("k-a"), b = idOf("k-b");

        Instant ackA = Instant.now();
        Thread.sleep(250);
        Instant ackB = Instant.now();

        try (Connection c = Db.open()) {
            Outbox.markPublishedOn(c, a, ackA);
            Outbox.markPublishedOn(c, b, ackB);
        }

        Instant gotA = publishedAt(a), gotB = publishedAt(b);
        assertTrue(gotA.isBefore(gotB),
                "two events acked a quarter second apart must not share a timestamp: "
                        + gotA + " vs " + gotB);

        // now() in Postgres is TRANSACTION time, not statement time. If the relay
        // ever wraps a batch in one transaction, every row in it collides on a
        // single instant and the trace flattens into a tie it then sorts
        // arbitrarily. Passing the instant in removes that trap entirely.
        assertFalse(gotA.equals(gotB), "distinct acks must produce distinct timestamps");
    }

    private static long idOf(String key) throws Exception {
        try (Connection c = Db.open();
             var ps = c.prepareStatement("SELECT id FROM outbox WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }

    private static Instant publishedAt(long id) throws Exception {
        try (Connection c = Db.open();
             var ps = c.prepareStatement("SELECT published_at FROM outbox WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getTimestamp(1).toInstant();
            }
        }
    }
}
