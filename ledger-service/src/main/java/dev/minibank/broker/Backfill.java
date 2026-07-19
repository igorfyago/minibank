package dev.minibank.broker;

import dev.minibank.ledger.Shard;
import dev.minibank.ledger.Shards;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * THE REPAIR · give the broker the history it never received.
 *
 * Before the trading path was unified, the bank could write an asset holding
 * straight into the ledger. Those trades are real: the customer's euros left,
 * the asset arrived, and the ledger recorded both legs in one double-entry
 * transaction. What is missing is the broker's side · no order, no fill, no
 * position, no cost basis. This walks the ledger's own record of those trades
 * and reconstructs the fills that should have existed.
 *
 * WHY RECONSTRUCT FILLS RATHER THAN JUST SET THE QUANTITY. Setting
 * positions.qty directly is fewer moving parts and it permanently breaks
 * {@link Broker#audit()}, which rebuilds every position from its fills and
 * reports whatever disagrees. Every repaired position would report drift
 * forever, and a muted alarm is worse than no alarm. The codebase already
 * states the principle: a projection you cannot recompute is not a
 * projection, it is a second source of truth that has not been caught lying
 * yet. So the repair writes HISTORY, and lets the projection be derived from
 * it exactly like every other position.
 *
 * NOTHING IS GUESSED. One ledger transaction carries both legs · the
 * customer's cash and the customer's units · so the quantity is a fact, the
 * cash is a fact, and the price is their ratio. No price feed is consulted,
 * which matters because a feed would answer with TODAY'S price for a trade
 * that happened whenever it happened. The fee is zero because it genuinely
 * was: the retired path never charged one.
 *
 * WHY IT REPLAYS CHRONOLOGICALLY INSTEAD OF APPENDING. Average cost is
 * order-dependent · a sell realises against the average at that moment · so
 * bolting reconstructed fills onto the end of the history would produce the
 * right final quantity with the wrong cost basis and the wrong realised P&L.
 * Merging them into the timeline and replaying yields the numbers that would
 * exist if these trades had always gone through the broker. Realised P&L is
 * a REPORTED number, so for affected customers this is a restatement, and it
 * should be described as one rather than slipped in quietly.
 *
 * THE THING THAT COULD GO BADLY WRONG, written down so it stays fixed. The
 * normal fill path (Broker#recordFill) writes an order.filled outbox row in
 * the same commit as the fill. If this repair reused that path, the relay
 * would publish every reconstructed fill, the ledger would consume them, and
 * every one of these trades would settle a SECOND time · double-charging the
 * customer for a purchase they already made. So this class has its own
 * insert path, it writes orders, fills and positions and nothing else, and
 * it never opens a ledger connection for writing. The ledger is read from,
 * only, and only to find out what already happened.
 *
 * IDEMPOTENT, by two independent gates: the fill id is derived from the
 * ledger transaction id, and the order's client_order_id is UNIQUE. A second
 * run inserts nothing and recomputes the same positions. The append-only
 * trigger on fills means a run that somehow tried to REWRITE one would be
 * refused by the database rather than by this code's good intentions.
 */
public final class Backfill {

    /** What one run did. Empty and zeroed is the healthy steady state. */
    public record Report(int fillsCreated, int positionsRebuilt, List<String> skipped) {
        public boolean changedAnything() {
            return fillsCreated > 0 || positionsRebuilt > 0;
        }

        @Override
        public String toString() {
            return "backfill: " + fillsCreated + " fill(s) reconstructed, "
                    + positionsRebuilt + " position(s) rebuilt"
                    + (skipped.isEmpty() ? "" : ", " + skipped.size() + " skipped");
        }
    }

    /** One ledger-recorded trade that never reached the broker. */
    private record LedgerTrade(UUID txId, long customerId, String symbol, String side,
                               BigDecimal qty, BigDecimal price, Timestamp at) {}

    private Backfill() {}

    /**
     * Reconstruct everything missing, then rebuild what that touched.
     *
     * Safe to run on every boot: once the books agree it finds nothing to
     * insert and rebuilds nothing, so it costs two queries and changes no
     * rows. Safe to run while the bank is serving traffic: it never writes to
     * the ledger and it never publishes an event.
     */
    public static Report run() throws SQLException {
        List<String> skipped = new ArrayList<>();
        Set<String> touched = new LinkedHashSet<>();
        int created = 0;

        Set<String> listed = new LinkedHashSet<>();
        for (Catalog.Instrument i : Catalog.all()) listed.add(i.symbol());

        for (LedgerTrade t : ledgerTradesWithoutBroker()) {
            if (!listed.contains(t.symbol())) {
                // an instrument the broker does not list cannot have a broker
                // position, and listing one is a business decision with
                // regulatory weight · a repair does not get to make it
                skipped.add(t.customerId() + "/" + t.symbol()
                        + ": not listed in the broker's catalog · list it, then re-run");
                continue;
            }
            if (insert(t)) created++;
            // EVERY candidate is rebuilt, whether or not THIS run inserted it.
            //
            // These used to be the same flag: a key entered `touched` only
            // when insert() returned true, and insert() returns false as soon
            // as the fill id exists. But fills are committed one transaction
            // at a time inside this loop and every rebuild happens after it,
            // so a run interrupted between the two phases left committed fills
            // with an unrebuilt projection · and because the fills were now
            // present, every later run reported zero and rebuilt nothing. The
            // repair that exists to fix drift insisted there was nothing to
            // do, permanently. An idempotency gate answers "did I already
            // write this"; it cannot also answer "is the projection correct",
            // and using one flag for both questions is what made the class
            // comment's promise ("a second run recomputes the same positions")
            // false in exactly the case that needed it.
            touched.add(t.customerId() + "/" + t.symbol());
        }

        // rebuild() is idempotent and cheap, so running it on a healthy key
        // costs a query and changes nothing · and it reports whether the
        // stored position actually MOVED, which keeps the steady-state report
        // honestly zero instead of claiming work it did not do
        int rebuilt = 0;
        for (String key : touched) {
            long customerId = Long.parseLong(key.substring(0, key.indexOf('/')));
            if (rebuild(customerId, key.substring(key.indexOf('/') + 1))) rebuilt++;
        }
        return new Report(created, rebuilt, skipped);
    }

    // ------------------------------------------------------------------ read the ledger

    /**
     * Every trade the retired path wrote, with both of the CUSTOMER'S legs.
     *
     * The join through asset_accounts is what picks the customer's asset
     * entry out of a four-entry transaction: two of those entries are the
     * broker's clearing legs and one is the customer's cash. Matching on the
     * recorded holding mapping rather than on a derived account id means this
     * reads the same fact the trade itself wrote, which is the difference
     * between reading history and re-deriving it.
     *
     * Ordered by time, because the replay that follows depends on the order.
     */
    private static List<LedgerTrade> ledgerTradesWithoutBroker() throws SQLException {
        List<LedgerTrade> out = new ArrayList<>();
        for (Shard s : Shards.all()) {
            try (Connection c = s.open();
                 PreparedStatement ps = c.prepareStatement("""
                         SELECT t.id, t.kind, t.created_at, aa.customer_id, aa.symbol,
                                ce.amount AS cash, ae.amount AS units
                         FROM transactions t
                         JOIN entries ae      ON ae.tx_id = t.id
                         JOIN asset_accounts aa ON aa.account_id = ae.account_id
                         JOIN entries ce      ON ce.tx_id = t.id AND ce.account_id = aa.customer_id
                         WHERE t.kind LIKE 'trade:%'
                         ORDER BY t.created_at, t.id""");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    BigDecimal units = rs.getBigDecimal(7), cash = rs.getBigDecimal(6);
                    if (units == null || cash == null || units.signum() == 0) continue;
                    // the SIGN of the customer's own asset leg says which way
                    // this went · more reliable than re-parsing the kind, and
                    // it agrees with it by construction
                    String side = units.signum() > 0 ? "buy" : "sell";
                    BigDecimal qty = units.abs();
                    BigDecimal price = cash.abs().divide(qty, 8, RoundingMode.HALF_UP);
                    if (price.signum() <= 0) continue;      // fills CHECK price > 0
                    out.add(new LedgerTrade(rs.getObject(1, UUID.class), rs.getLong(4),
                            rs.getString(5), side, qty, price, rs.getTimestamp(3)));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ write the broker

    /** The deterministic id · the same trick the saga's refusal already uses,
     *  and the reason a second run is a no-op rather than a second position. */
    static UUID fillIdFor(UUID ledgerTxId) {
        return UUID.nameUUIDFromBytes(("backfill:" + ledgerTxId).getBytes(StandardCharsets.UTF_8));
    }

    /** @return true if this run created the rows, false if they were already there */
    private static boolean insert(LedgerTrade t) throws SQLException {
        UUID fillId = fillIdFor(t.txId());
        String clientOrderId = "backfill:" + t.txId();
        try (Connection c = BrokerDb.open()) {
            c.setAutoCommit(false);
            try {
                if (exists(c, fillId)) { c.rollback(); return false; }
                UUID orderId = UUID.randomUUID();
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO orders(id, client_order_id, customer_id, symbol, side, qty, notional,
                                           order_type, limit_px, status, venue, created_at, updated_at)
                        VALUES (?,?,?,?,?,?,NULL,'market',NULL,'settled','backfill',?,?)
                        ON CONFLICT (client_order_id) DO NOTHING""")) {
                    ps.setObject(1, orderId);
                    ps.setString(2, clientOrderId);
                    ps.setLong(3, t.customerId());
                    ps.setString(4, t.symbol());
                    ps.setString(5, t.side());
                    ps.setBigDecimal(6, t.qty());
                    ps.setTimestamp(7, t.at());
                    ps.setTimestamp(8, t.at());
                    if (ps.executeUpdate() == 0) {
                        // the order is already there from an interrupted run ·
                        // find it and hang the fill off it rather than
                        // stranding a second order with no fill.
                        //
                        // This branch used to roll back and return false,
                        // which is the exact opposite of what the comment
                        // above it promises: the order existed, the fill did
                        // not (we checked), and giving up left it stranded
                        // forever with no fill to derive a position from.
                        UUID existing = orderIdFor(c, clientOrderId);
                        if (existing == null) { c.rollback(); return false; }
                        orderId = existing;
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO fills(id, order_id, qty, price, fee, venue_fill_id, kind, executed_at)
                        VALUES (?,?,?,?,0,?, 'backfill', ?)
                        ON CONFLICT (id) DO NOTHING""")) {
                    ps.setObject(1, fillId);
                    ps.setObject(2, orderId);
                    ps.setBigDecimal(3, t.qty());
                    ps.setBigDecimal(4, t.price());
                    ps.setString(5, clientOrderId);
                    ps.setTimestamp(6, t.at());
                    ps.executeUpdate();
                }
                // NO OUTBOX ROW. See the class comment · this is the line that
                // stops the repair from settling every one of these trades a
                // second time.
                c.commit();
                return true;
            } catch (Exception e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    /** The order a previous interrupted run already wrote for this trade. */
    private static UUID orderIdFor(Connection c, String clientOrderId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM orders WHERE client_order_id = ?")) {
            ps.setString(1, clientOrderId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1, UUID.class) : null;
            }
        }
    }

    private static boolean exists(Connection c, UUID fillId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM fills WHERE id = ?")) {
            ps.setObject(1, fillId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Replay one position from its entire fill history.
     *
     * Uses {@link Broker#advance} · the same pure function the live path
     * uses, so a rebuilt position and an incrementally maintained one cannot
     * disagree about average cost. A compensation reverses its order's side,
     * the same sign rule audit() and flowsSince() already apply, because a
     * reversal is not a trade and counting it as one would put a purchase in
     * the book that nobody made.
     *
     * @return whether the stored projection actually moved · a rebuild that
     *         confirms a healthy position is not work done to it, and
     *         reporting it as such would make every run look like a repair.
     */
    static boolean rebuild(long customerId, String symbol) throws SQLException {
        try (Connection c = BrokerDb.open()) {
            Broker.Position before = Broker.positionOn(c, customerId, symbol);
            Broker.Position p = new Broker.Position(customerId, symbol,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT o.side, f.qty, f.price, f.fee, f.kind
                    FROM fills f JOIN orders o ON o.id = f.order_id
                    WHERE o.customer_id = ? AND o.symbol = ?
                    ORDER BY f.executed_at, f.id""")) {
                ps.setLong(1, customerId);
                ps.setString(2, symbol);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String side = rs.getString(1);
                        boolean reversal = "compensation".equals(rs.getString(5));
                        if (reversal) side = "buy".equals(side) ? "sell" : "buy";
                        p = Broker.advance(p, side, rs.getBigDecimal(2), rs.getBigDecimal(3),
                                reversal ? BigDecimal.ZERO : rs.getBigDecimal(4));
                    }
                }
            }
            Broker.Position after = new Broker.Position(customerId, symbol,
                    p.qty(), p.costBasis().setScale(8, RoundingMode.HALF_UP),
                    p.realizedPnl().setScale(8, RoundingMode.HALF_UP));
            Broker.store(c, after);
            return before.qty().compareTo(after.qty()) != 0
                    || before.costBasis().compareTo(after.costBasis()) != 0
                    || before.realizedPnl().compareTo(after.realizedPnl()) != 0;
        }
    }
}
