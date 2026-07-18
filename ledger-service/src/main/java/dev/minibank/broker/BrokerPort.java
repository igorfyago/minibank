package dev.minibank.broker;

import java.math.BigDecimal;

/**
 * THE VENUE PORT · the one seam the rest of this service is built around.
 *
 * Nothing above this interface knows where an order goes. That is the whole
 * point, and it is worth being precise about WHY, because "we might swap the
 * broker one day" is not a good enough reason to add an abstraction:
 *
 *   1. The order lifecycle is OURS. Accepted, filled, settled, rejected,
 *      idempotent on client_order_id, reconciled against fills · none of
 *      that changes when the venue does, so none of it should live in venue
 *      code. Put the lifecycle behind the port and every venue inherits it.
 *
 *   2. The venues genuinely differ, and not in ways you can paper over. A
 *      simulated venue fills instantly at a known price. A real one fills
 *      asynchronously, partially, sometimes never, and tells you later over
 *      a socket. The port is therefore shaped for the HARD case: place()
 *      returns an ACKNOWLEDGEMENT, not a fill. Fills arrive separately.
 *      A port shaped for the easy case would have to be rewritten for the
 *      first real venue, which is the usual way this abstraction fails.
 *
 *   3. It makes the untested path visible. IbkrVenue below does not pretend:
 *      it refuses, loudly, rather than returning a plausible fake. An
 *      adapter that fabricates fills is worse than no adapter, because the
 *      book it produces looks real.
 */
public interface BrokerPort {

    /** How this venue identifies itself in the orders table and in events. */
    String name();

    /**
     * Route an order. Returns the venue's acknowledgement · NOT a fill.
     *
     * Implementations must be safe to call twice with the same
     * clientOrderId: the caller has already committed the order row, and a
     * retry after a network failure must not produce a second order at the
     * venue.
     */
    Ack place(OrderRequest request);

    /** What the venue says an instrument is worth right now. */
    BigDecimal quote(String symbol);

    // ------------------------------------------------------------------

    record OrderRequest(
            String clientOrderId,
            long customerId,
            String symbol,
            String side,          // buy | sell
            BigDecimal qty,       // null when the order is sized in money
            BigDecimal notional,  // null when the order is sized in units
            String orderType,     // market | limit
            BigDecimal limitPx) {}

    /** Accepted (and possibly already filled), or refused outright. */
    record Ack(boolean accepted, String venueRef, String rejectReason, Fill immediateFill) {

        public static Ack accepted(String venueRef) {
            return new Ack(true, venueRef, null, null);
        }

        /** A venue that fills synchronously hands the fill back with the ack. */
        public static Ack filled(String venueRef, Fill fill) {
            return new Ack(true, venueRef, null, fill);
        }

        public static Ack rejected(String reason) {
            return new Ack(false, null, reason, null);
        }
    }

    /** A fill as the VENUE reports it. Our own ids are added when we store it. */
    record Fill(BigDecimal qty, BigDecimal price, BigDecimal fee, String venueFillId) {}
}
