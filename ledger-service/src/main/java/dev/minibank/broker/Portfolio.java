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
 *
 *   5. AN EXPIRED CONTRACT IS NOT WORTH ZERO. It is worth whatever it settled
 *      for, and that is a number nobody here observed. Writing zero would be
 *      the same fabrication rule 1 refuses for a missing mark, with the same
 *      consequence: it renders as a total loss that may not have happened.
 *      What expiry DOES change is that the contract has stopped existing, so
 *      a stale mark must not be carried forward for it the way one legitimately
 *      is for a live instrument whose feed is momentarily down. An expired row
 *      is therefore drawn, flagged, and left unvalued, and it withholds the
 *      total exactly as an unpriced row does.
 *
 * EVERY MONEY NUMBER IS qty * price * MULTIPLIER. One option contract controls
 * a hundred shares. A share's multiplier is 1, so there is no branch anywhere
 * below asking whether a holding is an option before deciding how to value it ·
 * a stock is the ordinary case with the ordinary contract size. The multiplier
 * comes from the catalog, and a holding whose instrument is not in the catalog
 * has an UNKNOWN contract size, which is treated as unvaluable rather than
 * assumed to be one. Assuming one is right for a share and wrong by a factor
 * of a hundred for a contract, and the wrong answer looks exactly like the
 * right one.
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
    /**
     * multiplier is carried on the row so a screen can say "1 contract (100
     * shares)" without a second lookup, and so that value / qty is visibly not
     * the price. Null when the instrument is not in the catalog, which is the
     * same condition that makes the row unvaluable.
     *
     * expiresOn is null for anything that does not expire. priceSource reads
     * "expired" for a contract past its date, which is a state and not a
     * feed failure · "unavailable" would say the feed is down and imply the
     * number is coming back.
     */
    public record Holding(String symbol, String name, String exchange, String kind,
                          BigDecimal qty, BigDecimal avgCost, BigDecimal price, String priceSource,
                          BigDecimal value, BigDecimal costBasis,
                          BigDecimal unrealized, BigDecimal unrealizedPct, BigDecimal realized,
                          BigDecimal prevClose, BigDecimal dayChange, BigDecimal dayChangePct,
                          String dayBasis, BigDecimal multiplier,
                          java.time.LocalDate expiresOn) {}

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
                            /* the day move as a percentage of what the book was
                               worth at the prior close · null under exactly the
                               same gate as dayChange, and ALSO null when that
                               prior-close value is zero, which is the case for a
                               book opened entirely today. See dayBase. */
                            BigDecimal dayChangePct,
                            int holdings, int unpriced, int withoutPrevClose,
                            /* a hardcoded constant stood in for a price · the
                               total is withheld when this is non-zero */
                            int fabricated,
                            /* a real but possibly old mark · the total still
                               stands, and the screen says it may be stale */
                            int stale,
                            /* closed-to-flat rows: their realised P&L is in
                               the total but they have no row to point at */
                            int closedPositions,
                            /* contracts past their expiry date · they are held
                               and they are NOT valued, so the total is withheld
                               exactly as it is for an unpriced row. See rule 5. */
                            int expired) {}

    /**
     * ONE ASSET CLASS, and the subtotal of exactly the rows drawn under it.
     *
     * A group is the set of OPEN HOLDINGS of one kind. That sentence is the
     * whole contract, and it is worth stating because of what it excludes: a
     * position closed to flat is in no group, because it is not a holding and
     * there is no row to put under the band. Its realised P&L and its day move
     * are still real and still in the Aggregate, which is therefore allowed to
     * be larger than the sum of the groups. `closedPositions` on the Aggregate
     * says how many rows are in that gap, so a screen can account for the
     * difference instead of a reader discovering it with a calculator.
     *
     * THE SUBTOTALS DO NOT ALWAYS ADD UP TO THE HEADLINE, AND MUST NOT BE MADE
     * TO. Every figure here is summed unrounded and rounded ONCE, at its own
     * scale · so a band worth 249.875058 shows 249.88, a band worth 119.865524
     * shows 119.87, and the book worth 369.740581 shows 369.74 while those two
     * bands read 369.75. Observed live, not hypothesised. Each of the three is
     * the most accurate two-decimal statement of its own true value, and the
     * cent appears only when a reader adds the DISPLAYED numbers, which are
     * rounded, instead of the real ones, which are not.
     *
     * The tempting fix · make the headline the sum of the bands · is forbidden
     * by PortfolioLessonTest.lesson1: it would make the one number the customer
     * actually reads the sum of a column of rounded cents, drifting further
     * from the truth with every band added, in order to make an arithmetic
     * check pass that nobody performs. Pinned by lesson 10 in
     * PortfolioGroupingLessonTest so it cannot be "corrected" later.
     *
     * The withholding rules apply here at group scope, unchanged and by
     * construction rather than by re-implementation · one unpriced stock nulls
     * the Stocks subtotal and leaves the Crypto subtotal standing. That is the
     * point of subtotals: an incomplete total is not a total, but it is only
     * ITS OWN total that it spoils.
     *
     * A group with no holdings is never built. An empty band with a €0.00
     * subtotal is a report about a database, and the screen would have to
     * apologise for it.
     */
    public record Group(String kind, String label, int holdings,
                        BigDecimal marketValue, BigDecimal costBasis,
                        BigDecimal unrealized, BigDecimal unrealizedPct,
                        BigDecimal dayChange, BigDecimal dayChangePct,
                        int unpriced, int withoutPrevClose, int stale, int expired) {}

    public record Snapshot(Aggregate aggregate, List<Holding> holdings, List<Group> groups) {}

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * The asset classes in the order a portfolio screen reads them, and what
     * they are called.
     *
     * This lives here rather than in the template for the same reason
     * dayBasis() does: "an option contract belongs in the Options band" is a
     * statement about the instrument, not about a pixel. The list is an
     * ORDERING, not a whitelist · anything whose kind is not named here still
     * gets a group, appended after these and labelled with its own kind. So
     * listing a fund is a Catalog.list(...) call and not a UI change, which is
     * the property that stops the next asset class from being a rewrite.
     */
    private static final List<String> KIND_ORDER = List.of("equity", "option", "crypto");

    private static String groupLabel(String kind) {
        if (kind == null) return "Other";
        return switch (kind) {
            case "equity" -> "Stocks";
            case "option" -> "Options";
            case "crypto" -> "Crypto";
            default -> kind.substring(0, 1).toUpperCase() + kind.substring(1);
        };
    }

    /**
     * A running total and the reasons it might not be one.
     *
     * The aggregate and every group accumulate through THIS type and finish
     * through the same two methods below, so the honesty rules are written once
     * and cannot drift apart at the two scales. The earlier shape · sum the
     * aggregate inline, and let a screen reduce the rows for a subtotal · would
     * have re-derived group totals from a column of already-rounded cents, in
     * JavaScript, where rule 3 would have had to be re-implemented and would
     * eventually not have been.
     */
    private static final class Acc {
        BigDecimal marketValue = BigDecimal.ZERO, costBasis = BigDecimal.ZERO;
        BigDecimal realized = BigDecimal.ZERO;
        BigDecimal dayChange = BigDecimal.ZERO;
        /* what the contributing rows were worth at the prior close · the only
           honest denominator for a day percentage. A position opened inside the
           window adds to dayChange and NOT to this, correctly: it had no value
           at the prior close to be a percentage of. A book made entirely of
           such positions therefore has a base of zero and no percentage. */
        BigDecimal dayBase = BigDecimal.ZERO;
        int holdings, unpriced, withoutPrevClose, fabricated, stale, expired;

        /** Rule 3, in one place. */
        boolean whole() { return unpriced == 0 && fabricated == 0 && expired == 0; }

        BigDecimal value()      { return whole() ? money(marketValue) : null; }
        BigDecimal unrealized() { return whole() ? money(marketValue.subtract(costBasis)) : null; }

        BigDecimal unrealizedPct() {
            if (!whole() || costBasis.signum() == 0) return null;
            return marketValue.subtract(costBasis).multiply(HUNDRED)
                    .divide(costBasis.abs(), 2, RoundingMode.HALF_UP);
        }

        /* an EMPTY book is complete, not unknown · it is worth zero, and zero
           is a number we can stand behind */
        BigDecimal day()  { return withoutPrevClose > 0 ? null : money(dayChange); }

        BigDecimal dayPct() {
            if (withoutPrevClose > 0 || dayBase.signum() == 0) return null;
            return dayChange.multiply(HUNDRED).divide(dayBase.abs(), 2, RoundingMode.HALF_UP);
        }
    }

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
                                 Map<String, Broker.DayFlow> flows,
                                 java.time.LocalDate asOf) {

        List<Holding> holdings = new ArrayList<>();
        Acc agg = new Acc();
        // insertion-ordered so an unknown kind lands in first-seen order once
        // the named ones have been drained · see KIND_ORDER
        Map<String, Acc> byKind = new java.util.LinkedHashMap<>();

        for (Broker.Position p : positions) {
            agg.realized = agg.realized.add(p.realizedPnl());

            Catalog.Instrument meta = catalog.get(p.symbol());
            Quote q = quotes.getOrDefault(p.symbol(), Quote.none());
            Broker.DayFlow flow = flows.getOrDefault(p.symbol(), Broker.DayFlow.NONE);

            // THE CONTRACT SIZE, or null if we do not know it. Null means the
            // instrument is not in the catalog, and an uncatalogued holding
            // cannot be valued: the only guess available is 1, which is right
            // for a share and wrong by a hundred for a contract, and both
            // answers render identically.
            BigDecimal multiplier = meta == null ? null : meta.multiplier();
            // A contract past its expiry has stopped existing. Whatever it
            // settled for, we did not observe it · so it is not valued, and
            // any mark still lying around for it is not carried forward.
            boolean isExpired = meta != null && meta.expiredAsOf(asOf);
            boolean valuable = multiplier != null && !isExpired;

            // A position SOLD OUT TODAY is flat now and still moved money
            // today. Skipping it here (as this loop used to, by continuing
            // before the day block) dropped a real intraday gain or loss out
            // of the headline while still presenting that headline as
            // complete · the aggregate would silently disagree with the
            // customer's own day. The formula already handles it: qtyPrior
            // comes out as what they held at the prior close, and the traded
            // leg carries what they got for it.
            if (p.qty().signum() == 0) {
                // A row that did not trade today contributes nothing to the
                // day and cannot spoil it. One that DID trade today owes the
                // total a number, and if we cannot compute that number the
                // total is incomplete · rule 3 applies here exactly as it does
                // to an open holding.
                //
                // This is where the counter used to be missing. The guard
                // failing fell straight through to `continue` without
                // incrementing withoutPrevClose, so the aggregate below
                // (gated on withoutPrevClose == 0) still rendered a figure ·
                // a customer who sold out at a gain and then lost the feed
                // watched that gain disappear from the headline while the
                // headline went on presenting itself as their complete day.
                // The open-position path has always counted correctly in its
                // else; the closed one never did.
                if (flow.qty().signum() != 0) {
                    if (q.observed() && q.prevClose() != null && valuable) {
                        BigDecimal qtyPrior = p.qty().subtract(flow.qty());
                        // both legs are in un-multiplied notional (DayFlow's
                        // notional is SUM(qty*price) straight off the fills),
                        // so the contract size applies once, to their sum
                        agg.dayChange = agg.dayChange.add(
                                qtyPrior.multiply(q.price().subtract(q.prevClose()))
                                        .add(flow.qty().multiply(q.price()).subtract(flow.notional()))
                                        .multiply(multiplier));
                        // they DID hold this at the prior close · that value was
                        // part of the book the day is a percentage of, and
                        // leaving it out of the base while its gain is in the
                        // numerator would overstate the day's percentage by
                        // exactly the position they closed
                        if (qtyPrior.signum() > 0)
                            agg.dayBase = agg.dayBase.add(
                                    qtyPrior.multiply(q.prevClose()).multiply(multiplier));
                    } else {
                        agg.withoutPrevClose++;
                    }
                }
                // NOT added to any group. A group is the rows drawn beneath its
                // band, and there is no row for a position you no longer hold.
                continue;                                 // counted, not drawn
            }

            // FROM HERE DOWN THE POSITION IS DRAWN, so it belongs to a group.
            // Everything the aggregate accumulates below, the group
            // accumulates identically · same fields, same order, same rules.
            String kind = meta == null ? null : meta.kind();
            Acc grp = byKind.computeIfAbsent(kind, k -> new Acc());
            grp.holdings++;

            agg.costBasis = agg.costBasis.add(p.costBasis());
            grp.costBasis = grp.costBasis.add(p.costBasis());
            grp.realized  = grp.realized.add(p.realizedPnl());

            BigDecimal value = null, unrealized = null, unrealizedPct = null;
            if (q.stale() && valuable) { agg.stale++; grp.stale++; }
            if (isExpired) {
                // Rule 5. NOT zero, and NOT the last mark either · this
                // contract no longer trades, so the most recent price anyone
                // saw for it is not a current one and never will be again.
                agg.expired++; grp.expired++;
            } else if (multiplier == null) {
                // an unknown contract size is an unknown value, not a value of
                // qty * price · see the class comment
                agg.unpriced++; grp.unpriced++;
            } else if (q.observed()) {
                // THE MULTIPLICATION. qty * price * multiplier, with a share's
                // multiplier being 1, so this one line is correct for every
                // instrument and there is no option branch anywhere.
                value = p.qty().multiply(q.price()).multiply(multiplier);
                // costBasis already carries the multiplier: it was accumulated
                // from Broker.consideration, which applied it at fill time. So
                // this subtraction is money minus money and needs no scaling.
                unrealized = value.subtract(p.costBasis());
                if (p.costBasis().signum() != 0)
                    unrealizedPct = unrealized.multiply(HUNDRED)
                            .divide(p.costBasis().abs(), 2, RoundingMode.HALF_UP);
                agg.marketValue = agg.marketValue.add(value);
                grp.marketValue = grp.marketValue.add(value);
            } else if (q.priced()) {
                agg.fabricated++; grp.fabricated++;   // a constant wearing a price's clothes
            } else {
                agg.unpriced++; grp.unpriced++;
            }

            // the day move, corrected for what traded inside the window
            BigDecimal change = null, changePct = null;
            if (q.observed() && q.prevClose() != null && valuable) {
                BigDecimal qtyPrior = p.qty().subtract(flow.qty());
                BigDecimal held = qtyPrior.multiply(q.price().subtract(q.prevClose()));
                BigDecimal traded = flow.qty().multiply(q.price()).subtract(flow.notional());
                change = held.add(traded).multiply(multiplier);
                agg.dayChange = agg.dayChange.add(change);
                grp.dayChange = grp.dayChange.add(change);
                // a position that did not exist at the prior close has no
                // starting value to be a percentage of
                if (qtyPrior.signum() > 0) {
                    // the multiplier cancels between change and base, so the
                    // percentage is the same either way · it is applied to both
                    // anyway rather than relying on that, because a reader
                    // should not have to prove an algebraic identity to check
                    // that a displayed percentage is right
                    BigDecimal base = qtyPrior.multiply(q.prevClose()).multiply(multiplier);
                    // the SAME base feeds the row's percentage and the two
                    // scales above it, so a subtotal cannot disagree with the
                    // rows it sits over about what the day is a fraction of
                    agg.dayBase = agg.dayBase.add(base);
                    grp.dayBase = grp.dayBase.add(base);
                    if (base.signum() != 0)
                        changePct = change.multiply(HUNDRED).divide(base.abs(), 2, RoundingMode.HALF_UP);
                }
            } else {
                agg.withoutPrevClose++; grp.withoutPrevClose++;
            }

            // An expired contract shows NO price and NO prior close. Not
            // because we lack them · there may well be a mark cached from
            // before it expired · but because presenting a dead contract's
            // last premium in a "Last" column states that it is the current
            // price of something the customer still holds, and it is neither.
            holdings.add(new Holding(
                    p.symbol(),
                    meta == null ? null : meta.displayName(),
                    meta == null ? null : meta.exchange(),
                    meta == null ? null : meta.kind(),
                    p.qty(),
                    money(p.averageCost()),
                    isExpired || q.price() == null ? null : money(q.price()),
                    isExpired ? "expired" : q.source(),
                    money(value), money(p.costBasis()),
                    money(unrealized), unrealizedPct, money(p.realizedPnl()),
                    isExpired || q.prevClose() == null ? null : money(q.prevClose()),
                    money(change), changePct,
                    dayBasis(meta),
                    multiplier,
                    meta == null ? null : meta.expiresOn()));
        }

        // THE BANDS, in reading order. KIND_ORDER first and in its order, then
        // anything else in the order it was seen · an unrecognised kind gets a
        // band of its own rather than being dropped or quietly filed under
        // equities, because a holding nobody named is still a holding and
        // lumping it in with stocks would make the Stocks subtotal wrong.
        List<Group> groups = new ArrayList<>();
        List<String> order = new ArrayList<>(KIND_ORDER);
        for (String k : byKind.keySet()) if (!order.contains(k)) order.add(k);
        for (String kind : order) {
            Acc g = byKind.get(kind);
            if (g == null) continue;      // no holdings of this kind · no band
            groups.add(new Group(kind, groupLabel(kind), g.holdings,
                    g.value(), money(g.costBasis), g.unrealized(), g.unrealizedPct(),
                    g.day(), g.dayPct(),
                    g.unpriced, g.withoutPrevClose, g.stale, g.expired));
        }

        // Rule 3: an incomplete total is not a total. Note the ordering ·
        // these are summed UNROUNDED and rounded once in Acc, so the aggregate
        // is not the sum of a column of already-rounded cents. That is also
        // why the groups above are accumulated in parallel rather than reduced
        // from the finished rows: reducing would have summed rounded cents,
        // and the two scales would disagree in the last decimal.
        // `expired` is inside Acc.whole() for exactly the reason `unpriced` is:
        // a holding whose value we cannot state makes the sum of the others
        // not the portfolio's value. An expired contract is held and unvalued,
        // so a total that quietly omitted it would be a smaller, wrong number
        // wearing the label of a bigger, right one.
        return new Snapshot(
                new Aggregate(agg.value(), money(agg.costBasis), agg.unrealized(), agg.unrealizedPct(),
                        money(agg.realized), agg.day(), agg.dayPct(),
                        holdings.size(), agg.unpriced, agg.withoutPrevClose,
                        agg.fabricated, agg.stale, positions.size() - holdings.size(), agg.expired),
                holdings, groups);
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
