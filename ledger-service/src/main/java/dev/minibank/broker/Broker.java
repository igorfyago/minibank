package dev.minibank.broker;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * THE ORDER LIFECYCLE · the part of a brokerage that is not the venue.
 *
 * Everything here is the same handful of ideas the ledger already runs on,
 * applied to a different noun:
 *
 *   IDEMPOTENCY    client_order_id is a UNIQUE index, and the index IS the
 *                  gate. A retried POST returns the original order instead
 *                  of placing a second one · the ledger does exactly this
 *                  with txId, and for exactly the same reason: the network
 *                  will retry, and the customer must not pay twice.
 *
 *   APPEND-ONLY    Fills are facts. They are never edited, and a database
 *                  trigger enforces it.
 *
 *   PROJECTION     positions is derived from fills and can always be rebuilt
 *                  from them (audit()), which is the same truth-versus-copy
 *                  arrangement as entries versus the cached balance. The
 *                  copy exists because reading a position should not mean
 *                  replaying a history.
 *
 *   ONE COMMIT     A fill, the position it moves and the event announcing it
 *                  are written in a single transaction with the outbox row.
 *                  The cash settlement that follows happens in a different
 *                  database owned by a different service, so it cannot join
 *                  this transaction · which is why it is a saga and not a
 *                  distributed one.
 *
 * COST BASIS is average cost. A buy adds cost; a sell realises the
 * difference between proceeds and the average cost of what left. It is one
 * row per position instead of a lot table, it is what a retail app shows
 * beside a fractional holding, and it is wrong for tax. FIFO lots are a
 * different product decision, not a better version of this one.
 */
public final class Broker {

    /** Kafka topic the broker OWNS: it publishes here, it never consumes here. */
    public static final String TOPIC_ORDERS = "orders";

    private final BrokerPort venue;

    public Broker(BrokerPort venue) {
        this.venue = venue;
    }

    public BrokerPort venue() {
        return venue;
    }

    // ------------------------------------------------------------------
    public record Order(UUID id, String clientOrderId, long customerId, String symbol, String side,
                        BigDecimal qty, BigDecimal notional, String orderType, BigDecimal limitPx,
                        String status, String rejectReason, String venueName, String venueRef) {}

    public record Position(long customerId, String symbol, BigDecimal qty,
                           BigDecimal costBasis, BigDecimal realizedPnl) {

        /** What one unit cost on average · undefined for a flat position. */
        public BigDecimal averageCost() {
            return qty.signum() == 0 ? BigDecimal.ZERO : costBasis.divide(qty, 8, RoundingMode.HALF_UP);
        }
    }

    /**
     * Place an order.
     *
     * The order row is committed BEFORE the venue is called. That ordering is
     * deliberate and it is the same argument as the outbox: if we called the
     * venue first and crashed, an order would exist in the world that this
     * service has no record of. Recording intent first means the worst case
     * is an order we know about that never reached the venue, which is
     * recoverable. The reverse is not.
     */
    public Order place(String clientOrderId, long customerId, String symbol, String side,
                       BigDecimal qty, BigDecimal notional, String orderType, BigDecimal limitPx)
            throws SQLException {

        try (Connection c = BrokerDb.open()) {
            Order existing = byClientId(c, clientOrderId);
            if (existing != null) return existing;          // the gate: same request, same answer

            UUID id = UUID.randomUUID();
            c.setAutoCommit(false);
            try {
                insertOrder(c, id, clientOrderId, customerId, symbol, side,
                        qty, notional, orderType, limitPx, venue.name());
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                // lost the race with a concurrent identical request · the
                // unique index did its job, so answer with what won
                Order raced = byClientId(c, clientOrderId);
                if (raced != null) return raced;
                throw e;
            } finally {
                c.setAutoCommit(true);
            }

            BrokerPort.Ack ack;
            try {
                ack = venue.place(new BrokerPort.OrderRequest(
                        clientOrderId, customerId, symbol, side, qty, notional, orderType, limitPx));
            } catch (RuntimeException e) {
                reject(c, id, "venue unavailable: " + e.getMessage());
                return byId(c, id);
            }

            if (!ack.accepted()) {
                reject(c, id, ack.rejectReason());
                return byId(c, id);
            }
            setVenueRef(c, id, ack.venueRef());

            // a venue that fills synchronously hands the fill straight back;
            // an async one will call recordFill() later, off its own stream
            if (ack.immediateFill() != null) recordFill(c, id, ack.immediateFill());
            return byId(c, id);
        }
    }

    /**
     * Record a fill against an order, move the position, and announce it ·
     * all in ONE transaction, outbox row included.
     *
     * Idempotent on the venue's fill id where the venue gives us one: a
     * redelivered fill from a socket must not double the position.
     */
    public void recordFill(Connection c, UUID orderId, BrokerPort.Fill fill) throws SQLException {
        c.setAutoCommit(false);
        try {
            if (fill.venueFillId() != null && fillAlreadyRecorded(c, fill.venueFillId())) {
                c.rollback();
                return;
            }
            Order order = byId(c, orderId);
            if (order == null) throw new IllegalArgumentException("no such order: " + orderId);

            UUID fillId = UUID.randomUUID();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO fills(id, order_id, qty, price, fee, venue_fill_id) VALUES (?,?,?,?,?,?)")) {
                ps.setObject(1, fillId);
                ps.setObject(2, orderId);
                ps.setBigDecimal(3, fill.qty());
                ps.setBigDecimal(4, fill.price());
                ps.setBigDecimal(5, fill.fee());
                ps.setString(6, fill.venueFillId());
                ps.executeUpdate();
            }

            applyToPosition(c, order.customerId(), order.symbol(), order.side(),
                    fill.qty(), fill.price(), fill.fee());

            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE orders SET status = 'filled', updated_at = now() WHERE id = ?")) {
                ps.setObject(1, orderId);
                ps.executeUpdate();
            }

            // the event the ledger will settle against · same commit as the
            // fill, because the alternative is a fill nobody ever pays for
            BigDecimal cash = fill.qty().multiply(fill.price()).setScale(2, RoundingMode.HALF_UP);
            BrokerDb.appendOutbox(c, TOPIC_ORDERS, "filled:" + fillId,
                    "{\"type\":\"order.filled\",\"fillId\":\"" + fillId +
                    "\",\"orderId\":\"" + orderId +
                    "\",\"customer\":" + order.customerId() +
                    ",\"symbol\":\"" + order.symbol() +
                    "\",\"side\":\"" + order.side() +
                    "\",\"qty\":\"" + fill.qty().toPlainString() +
                    "\",\"price\":\"" + fill.price().toPlainString() +
                    "\",\"fee\":\"" + fill.fee().toPlainString() +
                    "\",\"cash\":\"" + cash.toPlainString() + "\"}");
            c.commit();
        } catch (Exception e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
    }

    /**
     * The projection step · average cost in four lines, and the reason each
     * one is there.
     *
     * BUY   the position grows and so does its cost, fee included: what you
     *       paid to own it IS part of what it cost you.
     * SELL  proceeds minus fee, compared against the average cost of the
     *       units that left. That difference is realised · it stops being an
     *       opinion about the future and becomes a number. The remaining
     *       basis shrinks by the average, NOT by the sale price, which is
     *       what keeps the average of what is left unchanged.
     */
    private static void applyToPosition(Connection c, long customerId, String symbol, String side,
                                        BigDecimal qty, BigDecimal price, BigDecimal fee) throws SQLException {
        Position p = positionOn(c, customerId, symbol);
        BigDecimal newQty, newBasis, newRealized = p.realizedPnl();

        if ("buy".equals(side)) {
            newQty = p.qty().add(qty);
            newBasis = p.costBasis().add(qty.multiply(price)).add(fee);
        } else {
            if (qty.compareTo(p.qty()) > 0)
                throw new IllegalArgumentException("cannot sell " + qty.toPlainString()
                        + " " + symbol + ": position is " + p.qty().toPlainString());
            BigDecimal avg = p.averageCost();
            BigDecimal costOut = avg.multiply(qty);
            BigDecimal proceeds = qty.multiply(price).subtract(fee);
            newRealized = p.realizedPnl().add(proceeds).subtract(costOut);
            newQty = p.qty().subtract(qty);
            newBasis = p.costBasis().subtract(costOut);
            // a position closed to zero has no basis left · round the dust
            // away rather than carrying a fraction of a cent forever
            if (newQty.signum() == 0) newBasis = BigDecimal.ZERO;
        }

        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO positions(customer_id, symbol, qty, cost_basis, realized_pnl, updated_at)
                VALUES (?,?,?,?,?, now())
                ON CONFLICT (customer_id, symbol) DO UPDATE
                   SET qty = EXCLUDED.qty, cost_basis = EXCLUDED.cost_basis,
                       realized_pnl = EXCLUDED.realized_pnl, updated_at = now()""")) {
            ps.setLong(1, customerId);
            ps.setString(2, symbol);
            ps.setBigDecimal(3, newQty);
            ps.setBigDecimal(4, newBasis.setScale(8, RoundingMode.HALF_UP));
            ps.setBigDecimal(5, newRealized.setScale(8, RoundingMode.HALF_UP));
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------

    public static Position positionOn(Connection c, long customerId, String symbol) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT qty, cost_basis, realized_pnl FROM positions WHERE customer_id = ? AND symbol = ?")) {
            ps.setLong(1, customerId);
            ps.setString(2, symbol);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next())
                    return new Position(customerId, symbol, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
                return new Position(customerId, symbol, rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3));
            }
        }
    }

    public static List<Position> positions(long customerId) throws SQLException {
        List<Position> out = new ArrayList<>();
        try (Connection c = BrokerDb.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT symbol, qty, cost_basis, realized_pnl FROM positions "
                     + "WHERE customer_id = ? AND (qty <> 0 OR realized_pnl <> 0) ORDER BY symbol")) {
            ps.setLong(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    out.add(new Position(customerId, rs.getString(1), rs.getBigDecimal(2),
                            rs.getBigDecimal(3), rs.getBigDecimal(4)));
            }
        }
        return out;
    }

    public static List<Order> orders(long customerId, int limit) throws SQLException {
        List<Order> out = new ArrayList<>();
        try (Connection c = BrokerDb.open();
             PreparedStatement ps = c.prepareStatement(
                     ORDER_COLUMNS + " FROM orders WHERE customer_id = ? ORDER BY created_at DESC LIMIT ?")) {
            ps.setLong(1, customerId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(readOrder(rs));
            }
        }
        return out;
    }

    /**
     * THE AUDIT · rebuild every position from the fills and report what
     * disagrees with the stored projection.
     *
     * Exactly the ledger's drift audit, one noun over. A projection you
     * cannot recompute is not a projection, it is a second source of truth
     * that has not been caught lying yet.
     */
    public static List<String> audit() throws SQLException {
        List<String> drifted = new ArrayList<>();
        try (Connection c = BrokerDb.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT o.customer_id, o.symbol,
                            SUM(CASE WHEN o.side = 'buy' THEN f.qty ELSE -f.qty END) AS net_qty
                     FROM fills f JOIN orders o ON o.id = f.order_id
                     GROUP BY o.customer_id, o.symbol""");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long customer = rs.getLong(1);
                String symbol = rs.getString(2);
                BigDecimal fromFills = rs.getBigDecimal(3);
                BigDecimal stored = positionOn(c, customer, symbol).qty();
                if (stored.compareTo(fromFills) != 0)
                    drifted.add(customer + "/" + symbol + ": stored " + stored.toPlainString()
                            + " but fills say " + fromFills.toPlainString());
            }
        }
        return drifted;
    }

    // ------------------------------------------------------------------
    private static final String ORDER_COLUMNS =
            "SELECT id, client_order_id, customer_id, symbol, side, qty, notional, "
            + "order_type, limit_px, status, reject_reason, venue, venue_ref";

    private static Order readOrder(ResultSet rs) throws SQLException {
        return new Order(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3), rs.getString(4),
                rs.getString(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getString(8),
                rs.getBigDecimal(9), rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13));
    }

    public static Order byId(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(ORDER_COLUMNS + " FROM orders WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readOrder(rs) : null;
            }
        }
    }

    public static Order byClientId(Connection c, String clientOrderId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(ORDER_COLUMNS + " FROM orders WHERE client_order_id = ?")) {
            ps.setString(1, clientOrderId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readOrder(rs) : null;
            }
        }
    }

    private static void insertOrder(Connection c, UUID id, String clientOrderId, long customerId,
                                    String symbol, String side, BigDecimal qty, BigDecimal notional,
                                    String orderType, BigDecimal limitPx, String venueName) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO orders(id, client_order_id, customer_id, symbol, side, qty, notional,
                                   order_type, limit_px, status, venue)
                VALUES (?,?,?,?,?,?,?,?,?,'accepted',?)""")) {
            ps.setObject(1, id);
            ps.setString(2, clientOrderId);
            ps.setLong(3, customerId);
            ps.setString(4, symbol);
            ps.setString(5, side);
            ps.setBigDecimal(6, qty);
            ps.setBigDecimal(7, notional);
            ps.setString(8, orderType);
            ps.setBigDecimal(9, limitPx);
            ps.setString(10, venueName);
            ps.executeUpdate();
        }
    }

    private static void reject(Connection c, UUID id, String reason) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE orders SET status = 'rejected', reject_reason = ?, updated_at = now() WHERE id = ?")) {
            ps.setString(1, reason);
            ps.setObject(2, id);
            ps.executeUpdate();
        }
    }

    private static void setVenueRef(Connection c, UUID id, String ref) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE orders SET venue_ref = ?, updated_at = now() WHERE id = ?")) {
            ps.setString(1, ref);
            ps.setObject(2, id);
            ps.executeUpdate();
        }
    }

    private static boolean fillAlreadyRecorded(Connection c, String venueFillId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM fills WHERE venue_fill_id = ?")) {
            ps.setString(1, venueFillId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
