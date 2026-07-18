package dev.minibank.broker;

import java.math.BigDecimal;

/**
 * THE REAL-VENUE SEAM · not implemented, and it says so.
 *
 * This class exists to prove the port is the right shape, and to record
 * exactly what a real adapter has to do that the simulated one does not.
 * It refuses every call. It does NOT return plausible fills, because an
 * adapter that fabricates fills produces a book that looks real, and a book
 * that looks real is how a demo turns into a false claim.
 *
 * What a real implementation owes, and where the port already accounts for it:
 *
 *   ASYNC FILLS      IBKR (and Alpaca) acknowledge an order and report fills
 *                    later, over a socket, possibly in pieces, possibly
 *                    never. place() therefore returns an Ack with no fill,
 *                    and fills arrive through Broker.recordFill() from the
 *                    venue's stream. The lifecycle above the port already
 *                    handles partial and late fills; nothing there changes.
 *
 *   IDEMPOTENCY      Both venues accept a client order id. Ours is already
 *                    the primary idempotency gate, so a retried place() maps
 *                    onto the venue's own de-duplication rather than fighting
 *                    it. This is why clientOrderId is on OrderRequest.
 *
 *   RECONCILIATION   A real venue is the book of record for what it did. A
 *                    real adapter needs a periodic pull of the venue's fills
 *                    and positions, compared against ours, because a dropped
 *                    socket message is not a rare event. The projection
 *                    audit already knows how to rebuild from fills; this
 *                    would feed it.
 *
 *   MONEY IS REAL    Everything above is mechanics. The decision to point
 *                    this bank at a venue that moves real money is not a
 *                    mechanical one, and it is not made in a constructor.
 */
public final class IbkrVenue implements BrokerPort {

    private final String why;

    public IbkrVenue() {
        this("no live venue is configured, and this build does not route real orders");
    }

    public IbkrVenue(String why) {
        this.why = why;
    }

    @Override
    public String name() {
        return "ibkr";
    }

    @Override
    public Ack place(OrderRequest request) {
        throw new UnsupportedOperationException("ibkr adapter: " + why);
    }

    @Override
    public BigDecimal quote(String symbol) {
        throw new UnsupportedOperationException("ibkr adapter: " + why);
    }
}
