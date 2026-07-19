package dev.minibank.broker;

import dev.minibank.ledger.PriceFeed;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * THE SIMULATED VENUE · the default, and the honest one.
 *
 * It fills immediately at the live price the bank already pays for
 * (CoinGecko for crypto, Yahoo through the FX service for equities), applies
 * a spread and a fee so the numbers are not free money, and reports the fill
 * the same way a real venue would.
 *
 * It is a SIMULATION and the whole product says so out loud. The point of
 * this service is the order lifecycle, the settlement saga and the cost
 * basis · all of which are identical whether the fill came from here or from
 * a broker, which is exactly what the port is for.
 *
 * A limit order that is not marketable is REJECTED rather than rested: this
 * venue has no book to rest it on, and pretending otherwise would be the
 * kind of fake that makes a demo lie. Resting orders is a real feature and
 * it needs a real matching engine.
 */
public final class SimulatedVenue implements BrokerPort {

    /** Half-spread charged against the mid, both ways. 5 bps. */
    private static final BigDecimal SPREAD = new BigDecimal("0.0005");
    /** Commission, 10 bps of notional · a retail-ish number, not zero. */
    private static final BigDecimal FEE_RATE = new BigDecimal("0.0010");

    @Override
    public String name() {
        return "simulated";
    }

    @Override
    public BigDecimal quote(String symbol) {
        return PriceFeed.get(symbol.toLowerCase()).price();
    }

    @Override
    public Ack place(OrderRequest r) {
        // THE EXPIRY CHECK COMES BEFORE THE QUOTE, and the order matters.
        //
        // quote() below is PriceFeed.get, and PriceFeed's upstream-down branch
        // relabels the last price it saw as 'cached' with no age bound. Yahoo
        // answers 404 for a contract that has expired, so asking it about one
        // hands back that contract's FINAL PREMIUM wearing the same label a
        // live instrument's momentarily-stale mark wears · and this venue
        // would fill against it. A stale mark for something that still trades
        // is an old fact; a stale mark for something that has stopped
        // existing is not a fact about now at all, and the expiry date is the
        // only thing that tells those two 404s apart.
        //
        // Broker.place gates this too, and authoritatively, because it has to
        // hold for IbkrVenue as well. This copy is here because a venue that
        // cannot obtain a current price for an instrument must refuse on its
        // own account rather than rely on its caller having checked.
        try {
            Catalog.Instrument listed = Catalog.find(r.symbol());
            if (listed != null && listed.expiredAsOf(java.time.LocalDate.now(java.time.ZoneOffset.UTC)))
                return Ack.rejected("'" + r.symbol() + "' expired on " + listed.expiresOn()
                        + " · an expired contract no longer trades");
        } catch (Exception e) {
            return Ack.rejected("cannot establish whether " + r.symbol() + " is still listed");
        }

        BigDecimal mid = quote(r.symbol());
        if (mid == null || mid.signum() <= 0) return Ack.rejected("no price for " + r.symbol());

        // you cross the spread, always · buying costs more than mid, selling less
        boolean buy = "buy".equals(r.side());
        BigDecimal px = buy ? mid.multiply(BigDecimal.ONE.add(SPREAD))
                            : mid.multiply(BigDecimal.ONE.subtract(SPREAD));
        px = px.setScale(8, RoundingMode.HALF_UP);

        if ("limit".equals(r.orderType())) {
            boolean marketable = buy ? px.compareTo(r.limitPx()) <= 0
                                     : px.compareTo(r.limitPx()) >= 0;
            if (!marketable) {
                // no book to rest on · refusing is honest, faking a resting
                // order and never filling it would be worse
                return Ack.rejected("limit not marketable at " + px.stripTrailingZeros().toPlainString());
            }
            px = r.limitPx();
        }

        // WHAT ONE UNIT OF THIS INSTRUMENT COSTS · the price times the contract
        // size. For a share those are the same number; for an option contract
        // they differ by a hundred, and that difference decides how many of
        // them "50 euro worth" buys.
        BigDecimal multiplier;
        try {
            multiplier = Broker.multiplierOf(r.symbol());
        } catch (Exception e) {
            // an unlisted symbol has no known contract size · refusing to fill
            // is the honest answer, and it is the same answer the settlement
            // path would give a moment later anyway
            return Ack.rejected("no contract size for " + r.symbol());
        }
        BigDecimal unitCost = px.multiply(multiplier);

        // size it: either the caller said "3 units" or "50 euro worth"
        //
        // The notional branch divides by the UNIT COST, not by the price. It
        // used to divide by the price alone, which for an option would have
        // filled a hundred times too many contracts · "50 euro worth" would
        // have committed 5000 euro, and the venue would have reported it as a
        // fill the customer asked for.
        BigDecimal qty = r.qty() != null
                ? r.qty()
                : r.notional().divide(unitCost, 8, RoundingMode.DOWN);
        if (qty.signum() <= 0) return Ack.rejected("amount too small at this price");

        // the commission is a fraction of the NOTIONAL, and the notional of an
        // option contract includes its multiplier
        BigDecimal fee = qty.multiply(unitCost).multiply(FEE_RATE).setScale(8, RoundingMode.HALF_UP);
        return Ack.filled("sim-" + UUID.randomUUID(),
                new Fill(qty, px, fee, "simfill-" + UUID.randomUUID()));
    }
}
