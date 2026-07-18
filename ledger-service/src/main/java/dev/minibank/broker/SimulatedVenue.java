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

        // size it: either the caller said "3 units" or "50 euro worth"
        BigDecimal qty = r.qty() != null
                ? r.qty()
                : r.notional().divide(px, 8, RoundingMode.DOWN);
        if (qty.signum() <= 0) return Ack.rejected("amount too small at this price");

        BigDecimal fee = qty.multiply(px).multiply(FEE_RATE).setScale(8, RoundingMode.HALF_UP);
        return Ack.filled("sim-" + UUID.randomUUID(),
                new Fill(qty, px, fee, "simfill-" + UUID.randomUUID()));
    }
}
