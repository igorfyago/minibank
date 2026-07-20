package dev.minibank.broker;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

            // AN EXPIRED CONTRACT IS NOT TRADABLE, and this is the only gate
            // that says so on the way IN.
            //
            // Every other mention of expiredAsOf in this service is a
            // valuation read · Portfolio.build, BrokerApi.positions, the
            // bank's investments tile. All three refuse to price a dead
            // contract, and none of them ran before the money moved. The
            // order path gated on Catalog.exists alone, and the row is not
            // deleted at expiry, so a dead contract stayed buyable: the venue
            // asked PriceFeed, Yahoo answered 404, PriceFeed's upstream-down
            // branch handed back the contract's final premium relabelled
            // 'cached' with no age bound, and the fill settled real euros out
            // of the customer's account for an instrument this same codebase
            // then refuses to value. The resulting position withholds their
            // entire portfolio total forever, because Acc.whole() counts it.
            //
            // Selling is refused for the same reason and not as an oversight.
            // There is no bid for a contract that has stopped existing, so a
            // sell would fill at that same stale premium and pay out cash
            // against a price nobody quoted. Unwinding an expired position is
            // an expiry SETTLEMENT · a separate, deliberate act with a
            // settlement price · and not an order.
            //
            // It sits HERE rather than only in the venue because it must hold
            // for IbkrVenue too, and after the order row is committed because
            // recording intent first is this method's whole ordering rule: the
            // customer gets a rejected order with a reason, not a silent drop.
            Catalog.Instrument listed = Catalog.find(symbol);
            if (listed != null && listed.expiredAsOf(java.time.LocalDate.now(ZoneOffset.UTC))) {
                reject(c, id, "'" + symbol + "' expired on " + listed.expiresOn()
                        + " · an expired contract no longer trades");
                return byId(c, id);
            }

            // A CONTRACT IS INDIVISIBLE, and this is the one choke point that
            // can say so for every venue.
            //
            // orders.qty is NUMERIC(20,8) CHECK (qty > 0) · the schema was
            // built for 0.0013 BTC, so 0.37 contracts passes every database
            // gate and always will. The venues cannot be the gate either:
            // SimulatedVenue fills whatever quantity it is handed, and its
            // notional branch MANUFACTURES the fraction · "50 euro worth" of
            // a 5.00-premium contract divides out to 0.1 contracts with no
            // qty ever having passed through any caller's hands. So both
            // fractional shapes are refused here, where the instrument's kind
            // is already in hand and where the expiry gate above has settled
            // that this method is the authoritative place for tradability
            // rules that must hold for IbkrVenue too.
            //
            // The key is the KIND, never the multiplier · a hypothetical
            // multiplier-1 option still trades in whole contracts, and crypto
            // stays fractional however this table grows. Rejected AFTER the
            // order row commits, with a reason: the method's own ordering
            // rule, a recorded refusal rather than a silent drop.
            if (listed != null && "option".equals(listed.kind())) {
                if (qty == null) {
                    reject(c, id, "option orders are sized in whole contracts, not by notional"
                            + " · dividing money by a premium manufactures a fractional contract");
                    return byId(c, id);
                }
                if (qty.remainder(BigDecimal.ONE).signum() != 0) {
                    reject(c, id, "'" + symbol + "' trades in whole contracts · "
                            + qty.toPlainString() + " is not a whole number of them");
                    return byId(c, id);
                }
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
            // fill, because the alternative is a fill nobody ever pays for.
            //
            // THE SAME FUNCTION THE POSITION USES. This used to be
            // qty*price, with the fee excluded, while advance() added that
            // fee to the cost basis · so the venue's commission was charged
            // to the customer's basis and no money ever moved for it. The
            // books disagreed about MONEY by exactly the fee on every single
            // fill, and neither invariant could see it: reconciliation
            // compares quantities, and sum-zero cannot notice an entry that
            // was never written. One rule, called from both places, is the
            // only way those two numbers stay the same number.
            BigDecimal cash = consideration(order.side(), fill.qty(), fill.price(), fill.fee(),
                    multiplierOf(order.symbol()));
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
     * The ledger settled it · the order is done.
     *
     * Idempotent by being a narrowed UPDATE rather than a read-then-write:
     * a redelivered settlement matches no rows the second time.
     */
    public void markSettled(UUID fillId) throws SQLException {
        try (Connection c = BrokerDb.open();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE orders SET status = 'settled', updated_at = now()
                     WHERE id = (SELECT order_id FROM fills WHERE id = ?) AND status = 'filled'""")) {
            ps.setObject(1, fillId);
            ps.executeUpdate();
        }
    }

    /**
     * THE COMPENSATION · the money side refused, so the position comes back.
     *
     * This is the saga's unhappy path and the reason a saga is not just "two
     * commits in a row". The venue filled; we cannot un-fill it, and we
     * cannot edit the fill, because fills are append-only facts. So the
     * position is corrected the way a ledger corrects anything: by recording
     * the opposite movement, marked as what it is.
     *
     * Idempotent on the derived venue_fill_id, so a redelivered rejection
     * compensates once. Compensating twice would leave the customer short of
     * a position they never had, which is the classic saga bug.
     */
    public void compensate(UUID fillId, String reason) throws SQLException {
        try (Connection c = BrokerDb.open()) {
            c.setAutoCommit(false);
            try {
                String marker = "compensation:" + fillId;
                if (fillAlreadyRecorded(c, marker)) { c.rollback(); return; }

                Fill original = fillById(c, fillId);
                if (original == null) { c.rollback(); return; }
                Order order = byId(c, original.orderId());

                // the mirror image of what the fill did to the position
                String reverse = "buy".equals(order.side()) ? "sell" : "buy";
                applyToPosition(c, order.customerId(), order.symbol(), reverse,
                        original.qty(), original.price(), BigDecimal.ZERO);

                UUID compId = UUID.randomUUID();
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO fills(id, order_id, qty, price, fee, venue_fill_id, kind)
                        VALUES (?,?,?,?,0,?, 'compensation')""")) {
                    ps.setObject(1, compId);
                    ps.setObject(2, original.orderId());
                    ps.setBigDecimal(3, original.qty());
                    ps.setBigDecimal(4, original.price());
                    ps.setString(5, marker);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE orders SET status = 'rejected', reject_reason = ?, updated_at = now()
                        WHERE id = ?""")) {
                    ps.setString(1, "settlement refused: " + reason);
                    ps.setObject(2, original.orderId());
                    ps.executeUpdate();
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    private record Fill(UUID id, UUID orderId, BigDecimal qty, BigDecimal price) {}

    private static Fill fillById(Connection c, UUID fillId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, order_id, qty, price FROM fills WHERE id = ?")) {
            ps.setObject(1, fillId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Fill(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getBigDecimal(3), rs.getBigDecimal(4)) : null;
            }
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
        Position moved = advance(positionOn(c, customerId, symbol), side, qty, price, fee,
                multiplierOf(symbol));
        store(c, moved);
    }

    /**
     * The contract size for a symbol, or a refusal.
     *
     * FAILS CLOSED, exactly as AssetRegistry.bySymbol does, and for the same
     * reason: an unlisted symbol has no known contract size, and the only
     * available guess is 1, which is right for a share and wrong by a hundred
     * for a contract. Guessing here would put the error in the customer's cash
     * rather than in a stack trace, and a fill that cannot be priced correctly
     * is one that must not settle at all.
     */
    static BigDecimal multiplierOf(String symbol) throws SQLException {
        Catalog.Instrument i = Catalog.find(symbol);
        if (i == null)
            throw new IllegalStateException("'" + symbol + "' is not a listed instrument"
                    + " · its contract size is unknown, and settling on a guessed one"
                    + " misstates the cash leg by exactly that guess");
        return i.multiplier();
    }

    /**
     * The arithmetic on its own, as a pure function · one position in, one
     * position out, no database.
     *
     * It lives here rather than inline in applyToPosition because there is a
     * second caller that has to produce IDENTICAL numbers: the replay that
     * rebuilds a position from its whole fill history. Average cost is
     * order-dependent · a sell realises against the average at that moment ·
     * so "apply the next fill" and "replay every fill" must be the same rule
     * or a rebuilt position will quietly disagree with an incrementally
     * maintained one. Two copies of this rule would be two answers to what a
     * holding cost, which is the exact ambiguity a cost basis exists to
     * remove.
     */
    public static Position advance(Position p, String side, BigDecimal qty,
                                   BigDecimal price, BigDecimal fee, BigDecimal multiplier) {
        BigDecimal newQty, newBasis, newRealized = p.realizedPnl();

        if ("buy".equals(side)) {
            newQty = p.qty().add(qty);
            newBasis = p.costBasis().add(consideration(side, qty, price, fee, multiplier));
        } else {
            if (qty.compareTo(p.qty()) > 0)
                throw new IllegalArgumentException("cannot sell " + qty.toPlainString()
                        + " " + p.symbol() + ": position is " + p.qty().toPlainString());
            BigDecimal avg = p.averageCost();
            // averageCost is basis-per-CONTRACT (the basis already carries the
            // multiplier, the quantity does not), so this needs no multiplier
            // of its own · applying one here would double-count it
            BigDecimal costOut = avg.multiply(qty);
            BigDecimal proceeds = consideration(side, qty, price, fee, multiplier);
            newRealized = p.realizedPnl().add(proceeds).subtract(costOut);
            newQty = p.qty().subtract(qty);
            newBasis = p.costBasis().subtract(costOut);
            // a position closed to zero has no basis left · round the dust
            // away rather than carrying a fraction of a cent forever
            if (newQty.signum() == 0) newBasis = BigDecimal.ZERO;
        }
        return new Position(p.customerId(), p.symbol(), newQty, newBasis, newRealized);
    }

    /**
     * WHAT THE FILL ACTUALLY COSTS, in money · the one number both books use.
     *
     * A buy costs the notional PLUS the venue's commission; a sell returns the
     * notional MINUS it. That is not an accounting convention, it is what
     * leaves and arrives in the customer's account, and it is therefore both
     * the cash the ledger moves and the basis the position carries.
     *
     * IT ROUNDS HERE, ONCE, AND ON PURPOSE. Money is two decimals and quantity
     * is eight, so qty*price is very often not a payable amount. Rounding in
     * the settlement event while the position kept the unrounded product left
     * the two books disagreeing by up to half a cent per fill even at zero
     * fee · small, permanent, and invisible to a quantity-only invariant.
     * Rounding inside this function instead means the basis records what was
     * actually paid, which is what a cost basis is supposed to be.
     *
     * Being a pure function of (side, qty, price, fee) is what keeps
     * {@link Backfill#rebuild} honest: replaying a fill history reproduces the
     * same considerations, so a rebuilt position and an incrementally
     * maintained one still cannot disagree.
     */
    /**
     * THE MULTIPLIER IS A REQUIRED ARGUMENT, not an optional one.
     *
     * One option contract controls 100 shares, so the money that changes hands
     * is qty * price * multiplier. A share's multiplier is 1, which is why
     * there is no branch here asking whether this is an option: a stock is the
     * ordinary case with the ordinary contract size, not a special case that
     * skips a multiplication.
     *
     * There is deliberately NO four-argument overload defaulting it to one.
     * An overload would make "I forgot the contract size" and "this instrument
     * has a contract size of one" the same call, and the two differ by a
     * factor of a hundred in the customer's cash. Omitting it should be a
     * compile error, and it is.
     *
     * The multiplier scales the MONEY leg only. The quantity leg is untouched:
     * Reconciliation asserts that the ledger's asset balance equals the
     * broker's position quantity, so the ledger holds CONTRACTS, not the
     * shares they control. Multiplying units here would report a 100x
     * divergence on every option position.
     */
    public static BigDecimal consideration(String side, BigDecimal qty,
                                           BigDecimal price, BigDecimal fee,
                                           BigDecimal multiplier) {
        if (multiplier == null || multiplier.signum() <= 0)
            throw new IllegalArgumentException("consideration needs a positive contract size, not "
                    + multiplier + " · a zero multiplier settles every fill for nothing");
        BigDecimal gross = qty.multiply(price).multiply(multiplier);
        BigDecimal net = "buy".equals(side) ? gross.add(fee) : gross.subtract(fee);
        return net.setScale(2, RoundingMode.HALF_UP);
    }

    /** Write a position down · the projection's only writer. */
    static void store(Connection c, Position p) throws SQLException {
        long customerId = p.customerId();
        String symbol = p.symbol();
        BigDecimal newQty = p.qty(), newBasis = p.costBasis(), newRealized = p.realizedPnl();
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

    /**
     * WHAT TRADED SINCE A MOMENT · signed by direction, per symbol.
     *
     * This exists because the obvious day-P&L formula is wrong:
     *
     *     todayPnl = qty_now * (price - prevClose)
     *
     * qty_now is the CURRENT quantity. A position opened this morning gets
     * credited with a full day's move it was never exposed to, and the number
     * looks entirely reasonable while being nonsense. The fix needs to know
     * what moved during the window, so the fills that happened inside it can
     * be marked from their own price instead of from the prior close:
     *
     *     todayPnl = qty_at_prior_close * (price - prevClose)
     *              + (qty_traded * price - notional_traded)
     *
     * The second term telescopes: sum over today's fills of
     * sign*qty*(price - fillPrice) is price*net_qty - net_notional.
     *
     * A compensation reverses its order's side · the same sign rule as
     * audit(), and for the same reason. A reversal is not a trade, and
     * counting it as one would put a fill in the book that nobody made.
     */
    public record DayFlow(BigDecimal qty, BigDecimal notional) {
        public static final DayFlow NONE = new DayFlow(BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public static Map<String, DayFlow> flowsSince(long customerId, Instant since) throws SQLException {
        Map<String, DayFlow> out = new HashMap<>();
        try (Connection c = BrokerDb.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT o.symbol,
                            SUM(CASE WHEN (o.side = 'buy') <> (f.kind = 'compensation')
                                     THEN f.qty ELSE -f.qty END) AS net_qty,
                            SUM(CASE WHEN (o.side = 'buy') <> (f.kind = 'compensation')
                                     THEN f.qty * f.price ELSE -(f.qty * f.price) END) AS net_notional
                     FROM fills f JOIN orders o ON o.id = f.order_id
                     WHERE o.customer_id = ? AND f.executed_at >= ?
                     GROUP BY o.symbol""")) {
            ps.setLong(1, customerId);
            ps.setObject(2, OffsetDateTime.ofInstant(since, ZoneOffset.UTC));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    out.put(rs.getString(1), new DayFlow(rs.getBigDecimal(2), rs.getBigDecimal(3)));
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
     *
     * A compensation reverses its order's side, so it subtracts where a
     * trade adds. Getting that wrong is not a cosmetic bug: the audit would
     * report drift on every compensated order and, worse, would have counted
     * a reversal as a second purchase.
     */
    public static List<String> audit() throws SQLException {
        List<String> drifted = new ArrayList<>();
        try (Connection c = BrokerDb.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT o.customer_id, o.symbol,
                            SUM(CASE WHEN (o.side = 'buy') <> (f.kind = 'compensation')
                                     THEN f.qty ELSE -f.qty END) AS net_qty
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
