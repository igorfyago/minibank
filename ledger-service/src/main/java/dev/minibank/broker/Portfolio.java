package dev.minibank.broker;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * THE PORTFOLIO VIEW · every number a screen shows, and nothing it cannot prove.
 *
 * This class is deliberately PURE. It takes positions, a catalog, quotes and
 * what traded today, and returns the arithmetic. It opens no connection and
 * calls no feed, which is what makes the aggregate maths testable without a
 * database pretending to be a market · the alternative is asserting against
 * whatever bitcoin happened to cost while the suite ran.
 *
 * THE HONESTY RULES, in one place, because they are the actual product:
 *
 *   1. A missing price is NULL, never zero. A zero mark prices a holding at
 *      nothing, which is indistinguishable from a genuinely worthless one and
 *      renders as a catastrophic loss that did not happen.
 *
 *   2. A missing prior close is NULL, never zero. Zero renders as "flat
 *      today", which is a specific claim about the market that we cannot make.
 *
 *   3. An aggregate over an incomplete set is NULL, not a partial sum. If one
 *      of three holdings has no price, the sum of the other two is not the
 *      portfolio's value · it is a smaller, wrong number wearing the label of
 *      a bigger, right one. The counts say how many rows were missing so the
 *      UI can explain itself instead of just showing a dash.
 *
 *   4. CASH IS NOT HERE. Net liquidation is securities plus cash, cash lives
 *      in the ledger, and the broker cannot read the ledger's database · that
 *      boundary is the point of database-per-service, not an oversight. So
 *      this is securities only and says so in its name rather than quietly
 *      under-reporting a number the customer will read as their net worth.
 */
public final class Portfolio {

    private Portfolio() {}

    /** A mark and the reference it moved from · both nullable, on purpose. */
    public record Quote(BigDecimal price, BigDecimal prevClose, String source) {

        public static Quote none() {
            return new Quote(null, null, "unavailable");
        }

        public boolean priced() {
            return price != null && price.signum() > 0;
        }

        /**
         * A number that came from a MARKET, however long ago.
         *
         * PriceFeed.fallback() answers with literal constants (90000 for
         * bitcoin, 195 for Apple) so that a dead feed does not take the
         * whole page down. That is the right call for a row, which can badge
         * itself, and the wrong one for a TOTAL: summing a hardcoded number
         * into "Securities value" produces a portfolio valuation that looks
         * precise and was invented. An incomplete total is not a total, and
         * neither is a fabricated one.
         *
         * "cached" is different and deliberately still counts: it is a real
         * observed price that may be stale, so it is included and flagged
         * rather than discarded.
         */
        public boolean observed() {
            return priced() && !"fallback".equals(source);
        }

        public boolean stale() {
            return "cached".equals(source);
        }
    }

    /**
     * One row of the screen.
     *
     * dayChange and dayChangePct are null when the feed gave no prior close.
     * dayChangePct is ALSO null for a position opened during the window: the
     * percentage wants a starting value, and a position that started at zero
     * has none. The absolute change is still real and still shown.
     */
    public record Holding(String symbol, String name, String exchange, String kind,
                          BigDecimal qty, BigDecimal avgCost, BigDecimal price, String priceSource,
                          BigDecimal value, BigDecimal costBasis,
                          BigDecimal unrealized, BigDecimal unrealizedPct, BigDecimal realized,
                          BigDecimal prevClose, BigDecimal dayChange, BigDecimal dayChangePct,
                          String dayBasis) {}

    /**
     * The totals.
     *
     * realized is the one number that is always present: it is stored, not
     * marked, so no feed can take it away. Everything else depends on a price
     * we may not have.
     */
    public record Aggregate(BigDecimal marketValue, BigDecimal costBasis,
                            BigDecimal unrealized, BigDecimal unrealizedPct,
                            BigDecimal realized, BigDecimal dayChange,
                            int holdings, int unpriced, int withoutPrevClose,
                            /* a hardcoded constant stood in for a price · the
                               total is withheld when this is non-zero */
                            int fabricated,
                            /* a real but possibly old mark · the total still
                               stands, and the screen says it may be stale */
                            int stale,
                            /* closed-to-flat rows: their realised P&L is in
                               the total but they have no row to point at */
                            int closedPositions) {}

    public record Snapshot(Aggregate aggregate, List<Holding> holdings) {}

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * Build the view.
     *
     * Positions arrive as Broker.positions() returns them, which includes
     * rows closed to flat (qty 0, realised P&L not 0). Those are NOT
     * holdings · you do not hold them · but their realised P&L is a fact
     * about money that already moved and it belongs in the total. Dropping it
     * would make the portfolio's lifetime P&L quietly reset every time a
     * customer closed a position, which is the sort of bug that gets noticed
     * only by whoever is down the most.
     */
    public static Snapshot build(List<Broker.Position> positions,
                                 Map<String, Catalog.Instrument> catalog,
                                 Map<String, Quote> quotes,
                                 Map<String, Broker.DayFlow> flows) {

        List<Holding> holdings = new ArrayList<>();
        BigDecimal marketValue = BigDecimal.ZERO;
        BigDecimal costBasis = BigDecimal.ZERO;
        BigDecimal realized = BigDecimal.ZERO;
        BigDecimal dayChange = BigDecimal.ZERO;
        int unpriced = 0, withoutPrevClose = 0, fabricated = 0, stale = 0;

        for (Broker.Position p : positions) {
            realized = realized.add(p.realizedPnl());

            Catalog.Instrument meta = catalog.get(p.symbol());
            Quote q = quotes.getOrDefault(p.symbol(), Quote.none());
            Broker.DayFlow flow = flows.getOrDefault(p.symbol(), Broker.DayFlow.NONE);

            // A position SOLD OUT TODAY is flat now and still moved money
            // today. Skipping it here (as this loop used to, by continuing
            // before the day block) dropped a real intraday gain or loss out
            // of the headline while still presenting that headline as
            // complete · the aggregate would silently disagree with the
            // customer's own day. The formula already handles it: qtyPrior
            // comes out as what they held at the prior close, and the traded
            // leg carries what they got for it.
            if (p.qty().signum() == 0) {
                if (q.observed() && q.prevClose() != null && flow.qty().signum() != 0) {
                    BigDecimal qtyPrior = p.qty().subtract(flow.qty());
                    dayChange = dayChange.add(
                            qtyPrior.multiply(q.price().subtract(q.prevClose()))
                                    .add(flow.qty().multiply(q.price()).subtract(flow.notional())));
                }
                continue;                                 // counted, not drawn
            }

            costBasis = costBasis.add(p.costBasis());

            BigDecimal value = null, unrealized = null, unrealizedPct = null;
            if (q.stale()) stale++;
            if (q.observed()) {
                value = p.qty().multiply(q.price());
                unrealized = value.subtract(p.costBasis());
                if (p.costBasis().signum() != 0)
                    unrealizedPct = unrealized.multiply(HUNDRED)
                            .divide(p.costBasis().abs(), 2, RoundingMode.HALF_UP);
                marketValue = marketValue.add(value);
            } else if (q.priced()) {
                fabricated++;      // a constant wearing a price's clothes
            } else {
                unpriced++;
            }

            // the day move, corrected for what traded inside the window
            BigDecimal change = null, changePct = null;
            if (q.observed() && q.prevClose() != null) {
                BigDecimal qtyPrior = p.qty().subtract(flow.qty());
                BigDecimal held = qtyPrior.multiply(q.price().subtract(q.prevClose()));
                BigDecimal traded = flow.qty().multiply(q.price()).subtract(flow.notional());
                change = held.add(traded);
                dayChange = dayChange.add(change);
                // a position that did not exist at the prior close has no
                // starting value to be a percentage of
                if (qtyPrior.signum() > 0) {
                    BigDecimal base = qtyPrior.multiply(q.prevClose());
                    if (base.signum() != 0)
                        changePct = change.multiply(HUNDRED).divide(base.abs(), 2, RoundingMode.HALF_UP);
                }
            } else {
                withoutPrevClose++;
            }

            holdings.add(new Holding(
                    p.symbol(),
                    meta == null ? null : meta.displayName(),
                    meta == null ? null : meta.exchange(),
                    meta == null ? null : meta.kind(),
                    p.qty(),
                    money(p.averageCost()),
                    q.price() == null ? null : money(q.price()),
                    q.source(),
                    money(value), money(p.costBasis()),
                    money(unrealized), unrealizedPct, money(p.realizedPnl()),
                    q.prevClose() == null ? null : money(q.prevClose()),
                    money(change), changePct,
                    dayBasis(meta)));
        }

        // Rule 3: an incomplete total is not a total. Note the ordering ·
        // these are summed UNROUNDED and rounded once here, so the aggregate
        // is not the sum of a column of already-rounded cents.
        boolean whole = unpriced == 0 && fabricated == 0;
        BigDecimal aggValue = whole ? money(marketValue) : null;
        BigDecimal aggUnrealized = whole ? money(marketValue.subtract(costBasis)) : null;
        BigDecimal aggUnrealizedPct = null;
        if (whole && costBasis.signum() != 0)
            aggUnrealizedPct = marketValue.subtract(costBasis).multiply(HUNDRED)
                    .divide(costBasis.abs(), 2, RoundingMode.HALF_UP);
        // an EMPTY portfolio is complete, not unknown · it is worth zero, and
        // zero is a number we can stand behind
        BigDecimal aggDay = withoutPrevClose > 0 ? null : money(dayChange);

        return new Snapshot(
                new Aggregate(aggValue, money(costBasis), aggUnrealized, aggUnrealizedPct,
                        money(realized), aggDay, holdings.size(), unpriced, withoutPrevClose,
                        fabricated, stale, positions.size() - holdings.size()),
                holdings);
    }

    /**
     * What window "today" means for this instrument, stated rather than assumed.
     *
     * An equity's prior close is the last SESSION close; bitcoin's reference
     * is 24 hours ago. These are different windows and no amount of column
     * alignment makes them the same one, so each row carries its own label
     * and the screen can stop implying they match.
     */
    private static String dayBasis(Catalog.Instrument meta) {
        if (meta == null) return null;
        return "crypto".equals(meta.kind()) ? "24h" : "session";
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }
}
