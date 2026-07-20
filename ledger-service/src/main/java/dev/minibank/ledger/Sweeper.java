package dev.minibank.ledger;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * THE SWEEPER · the reconciler for money the saga left in the pipe.
 *
 * The cross-region saga compensates exactly one failure: the destination
 * rejects, and the bounce refunds the payer. Every OTHER way the second half
 * can fail to happen · the arrival event lost by a consumer, a partition of
 * bad luck between Kafka and the applier · used to have the same ending:
 * money that departed sits in the IN_TRANSIT clearing account forever, and
 * no invariant can see it. Sum-zero holds (the depart balanced), drift holds
 * (the cache agrees with the entries), the dead letters are empty (nothing
 * failed HERE, it failed by not happening). A stranded saga is not an error
 * state; it is an absence, and absences need something that goes LOOKING.
 *
 * This is that something. Periodically, for every shard as a source:
 *
 *   1. find 'depart' claims older than a threshold (younger ones are sagas
 *      in normal flight · Kafka and the relay measure in milliseconds, the
 *      sweeper in minutes)
 *   2. drop the ones any shard has an 'arrive' claim for, and the ones the
 *      source has a refund claim for · those sagas ENDED, in money or in
 *      compensation, which is the saga's whole promise
 *   3. RE-PUBLISH what remains: the original departed event, same
 *      deterministic key, same payload, appended to the source outbox so the
 *      at-least-once machinery redelivers it through the front door
 *
 * WHY RE-PUBLISHING CANNOT DOUBLE-PAY. The arrival is gated by the txId,
 * claimed INSIDE the effect transaction on the destination shard. A sweep
 * that races a slow arrival · or two sweepers on two instances, or a sweep
 * of a saga that completed a millisecond after step 2 looked · produces a
 * duplicate delivery, and a duplicate delivery settles into the gate as
 * AlreadyProcessed, moving nothing. That is the whole reason this design is
 * safe: the sweeper does not need to be right about whether the saga is
 * stranded, it only needs the redelivery to be harmless when it is wrong.
 *
 * WHAT IT DELIBERATELY DOES NOT DO: complete the saga itself. Calling
 * arrive() directly from here would be a second write path to the same
 * money, and the applier's dead-letter and retry doctrine would not cover
 * it. The sweeper only re-arms the machinery that already knows how.
 *
 * And it COUNTS what it finds (Metrics), because a sweeper that repairs
 * silently hides the failure rate it exists to expose: a pipe that loses an
 * event a day is a pipe that needs fixing, and the repair must not make
 * that invisible.
 */
public final class Sweeper {

    /** A departure younger than this is a saga in normal flight, not a
     *  problem. Kafka and the relay deliver in milliseconds; a minute of
     *  silence means the event is not coming back on its own. */
    static final long THRESHOLD_MS = 60_000;

    /** How often the fleet is swept. Same cadence discipline as the relay
     *  and the refresher: frequent enough that stranded money is measured
     *  in minutes, cheap enough to run without thinking about it. */
    static final long INTERVAL_MS = 30_000;

    /** How many stale departures one pass will examine per shard · a bound,
     *  not a promise, the next pass takes the rest. */
    private static final int BATCH = 200;

    /** What one pass saw and did. found counts sagas stranded past the
     *  threshold; republished counts the ones whose retry was re-armed
     *  (a stranded saga whose event is still sitting unpublished in the
     *  outbox is found but NOT republished · the relay already owes that
     *  delivery, and duplicating the row would turn a slow relay into a
     *  flood). */
    public record Report(int found, int republished) {}

    private Sweeper() {}

    /**
     * Production mode: forever, on a virtual thread · the same shape as
     * OutboxRelay.runLoop. The body is wrapped for the Refresher's reason:
     * a sweeper that throws once and dies looks exactly like a working one
     * until money quietly starts to strand.
     */
    public static Thread start() {
        return Thread.ofVirtual().name("sweeper").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Report r = sweepOnce(THRESHOLD_MS);
                    if (r.found() > 0)
                        System.out.println("sweeper: " + r.found() + " stranded saga(s), "
                                + r.republished() + " republished");
                } catch (Exception e) {
                    // transient failure: log and keep sweeping · the ledger
                    // holds the truth and the next pass reads it again
                    System.err.println("sweeper: " + e.getMessage());
                }
                try {
                    Thread.sleep(INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
    }

    /** One deterministic pass over every shard (tests call it directly). */
    public static Report sweepOnce(long olderThanMillis) throws SQLException {
        int found = 0, republished = 0;
        for (Shard source : Shards.all()) {
            for (UUID txId : staleDepartures(source, olderThanMillis)) {
                if (resolved(txId)) continue;   // ended in money or in compensation
                found++;
                Metrics.inc("minibank_ledger_events_total", "kind=\"sweep_found\"");
                if (republish(source, txId)) {
                    republished++;
                    Metrics.inc("minibank_ledger_events_total", "kind=\"sweep_republished\"");
                }
            }
        }
        return new Report(found, republished);
    }

    // ------------------------------------------------------------------
    /** 'depart' claims older than the threshold on this shard. The claim is
     *  the departure's own idempotency record, written inside the same
     *  transaction as the money · so this list cannot contain a departure
     *  that rolled back, and cannot miss one that committed. */
    private static List<UUID> staleDepartures(Shard source, long olderThanMillis) throws SQLException {
        List<UUID> out = new ArrayList<>();
        try (Connection c = source.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT id FROM transactions
                     WHERE kind = 'depart'
                       AND created_at < now() - (? * interval '1 millisecond')
                     ORDER BY created_at
                     LIMIT ?""")) {
            ps.setLong(1, olderThanMillis);
            ps.setInt(2, BATCH);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getObject(1, UUID.class));
            }
        }
        return out;
    }

    /**
     * Did this saga already end? An 'arrive' claim on ANY shard (any, not
     * "the destination", because a relocation can move the recipient between
     * departure and now) or the deterministic refund claim closes the case.
     * The refund id is recomputed exactly as Shard.refund derives it, so the
     * bounce is recognisable without a lookup table.
     */
    private static boolean resolved(UUID txId) throws SQLException {
        UUID refundId = UUID.nameUUIDFromBytes(("refund:" + txId).getBytes(StandardCharsets.UTF_8));
        for (Shard s : Shards.all()) {
            try (Connection c = s.open();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT id, kind FROM transactions WHERE id = ? OR id = ?")) {
                ps.setObject(1, txId);
                ps.setObject(2, refundId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID id = rs.getObject(1, UUID.class);
                        String kind = rs.getString(2);
                        if (id.equals(txId) && "arrive".equals(kind)) return true;   // landed
                        if (id.equals(refundId)) return true;                        // bounced
                        // id == txId with kind 'depart' is the departure
                        // itself · the very thing being swept, not an ending
                    }
                }
            }
        }
        return false;
    }

    /**
     * Re-arm the at-least-once machinery: append the departed event again,
     * same deterministic key ("departed:" + txId), same payload · the outbox
     * still holds the original bytes, and re-publishing THOSE means the
     * applier sees exactly what it would have seen the first time.
     *
     * Skipped when an unpublished row with this key already exists: that row
     * IS the pending retry (the relay is down or behind), and appending a
     * copy next to it would double the queue on every pass.
     */
    private static boolean republish(Shard source, UUID txId) throws SQLException {
        String key = "departed:" + txId;
        try (Connection c = source.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM outbox WHERE key = ? AND published_at IS NULL LIMIT 1")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return false;   // the relay already owes this delivery
                }
            }
            String payload = null;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT payload FROM outbox WHERE key = ? ORDER BY id DESC LIMIT 1")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) payload = rs.getString(1);
                }
            }
            if (payload == null) {
                // a depart claim with no outbox row should be impossible ·
                // they commit together. Say so rather than guessing a payload:
                // fabricating money-moving events is not this class's licence.
                System.err.println("sweeper: depart " + txId + " on " + source.name
                        + " has no outbox row to republish");
                return false;
            }
            Outbox.append(c, ShardApplier.TOPIC_PAYMENTS, key, payload);
            return true;
        }
    }
}
