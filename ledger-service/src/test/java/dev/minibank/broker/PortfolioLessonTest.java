package dev.minibank.broker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE PORTFOLIO · WHAT THE SCREEN MAY CLAIM, AND WHAT IT MUST ADMIT.
 *
 * Every lesson here is about the same tension. A portfolio screen is read by
 * someone deciding what to do with their money, so a number that is merely
 * plausible is worse than no number at all · a blank cell prompts a question,
 * a wrong cell prompts a trade.
 *
 *   lesson 1  two holdings sum, and the total is not the sum of rounded rows
 *   lesson 2  an empty portfolio is worth zero · zero is a fact, not a gap
 *   lesson 3  a closed position stops being a holding but keeps its P&L
 *   lesson 4  no prior close, no day change · and no partial total either
 *   lesson 5  a position opened today did not live through today's whole move
 *   lesson 6  a position that traded today is marked from its own fill price
 *   lesson 7  an unpriced holding withholds the total instead of shrinking it
 *
 * No database and no price feed: Portfolio.build is pure, which is the whole
 * reason it exists apart from the HTTP handler. Asserting portfolio maths
 * against a live market would test CoinGecko's uptime, not our arithmetic.
 */
class PortfolioLessonTest {

    static final long IGOR = 10;

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 1: two holdings sum · and the total is computed from unrounded values, not from the column")
    void lesson1_twoHoldingsSum() {
        Portfolio.Snapshot s = Portfolio.build(
                List.of(position("BTC", "0.5", "20000", "0"),
                        position("AAPL", "10", "1800", "0")),
                catalog(),
                Map.of("BTC", quote("50000", "49000"),
                       "AAPL", quote("200", "190")),
                Map.of());

        assertEquals(2, s.holdings().size());
        Portfolio.Aggregate a = s.aggregate();

        // 0.5 * 50000 = 25000, 10 * 200 = 2000
        assertEquals("27000.00", a.marketValue().toPlainString());
        assertEquals("21800.00", a.costBasis().toPlainString());
        assertEquals("5200.00", a.unrealized().toPlainString());
        // 5200 / 21800
        assertEquals("23.85", a.unrealizedPct().toPlainString());
        assertEquals(0, a.unpriced());

        // and the rows themselves
        Portfolio.Holding btc = holding(s, "BTC");
        assertEquals("25000.00", btc.value().toPlainString());
        assertEquals("5000.00", btc.unrealized().toPlainString());
        assertEquals("Bitcoin", btc.name());
        assertEquals("CRYPTO", btc.exchange());
        assertEquals("24h", btc.dayBasis(), "crypto has no close · the row says which window it means");
        assertEquals("session", holding(s, "AAPL").dayBasis());
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 2: an empty portfolio is worth ZERO · not null, because nothing is not unknown")
    void lesson2_emptyPortfolioIsZeroNotNull() {
        Portfolio.Snapshot s = Portfolio.build(List.of(), catalog(), Map.of(), Map.of());

        Portfolio.Aggregate a = s.aggregate();
        assertTrue(s.holdings().isEmpty());
        assertEquals("0.00", a.marketValue().toPlainString());
        assertEquals("0.00", a.costBasis().toPlainString());
        assertEquals("0.00", a.unrealized().toPlainString());
        assertEquals("0.00", a.realized().toPlainString());
        assertEquals("0.00", a.dayChange().toPlainString(),
                "THE POINT: an empty book moved zero today, and that is knowledge, not absence");
        assertEquals(0, a.holdings());
        assertEquals(0, a.unpriced());
        // percent of nothing is the one thing that stays null · there is no
        // denominator, and 0% would claim the portfolio was flat
        assertNull(a.unrealizedPct(), "0/0 is not 0%");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 3: a position closed to flat is not a holding · but its realised P&L is still money that moved")
    void lesson3_closedPositionKeepsItsRealisedPnl() {
        Portfolio.Snapshot s = Portfolio.build(
                List.of(position("BTC", "0", "0", "250"),        // sold out, up 250
                        position("AAPL", "10", "1800", "40")),
                catalog(),
                Map.of("AAPL", quote("200", "190")),
                Map.of());

        assertEquals(1, s.holdings().size(), "you do not hold what you sold");
        assertEquals("AAPL", s.holdings().get(0).symbol());

        Portfolio.Aggregate a = s.aggregate();
        assertEquals("290.00", a.realized().toPlainString(),
                "THE POINT: 250 of it came from a position that no longer exists");
        // and the closed row contributes nothing to the marked side
        assertEquals("2000.00", a.marketValue().toPlainString());
        assertEquals("1800.00", a.costBasis().toPlainString());
        assertEquals(0, a.unpriced(), "a flat position needs no price, so it is not missing one");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 4: no prior close, no day change · and the TOTAL is withheld rather than partially summed")
    void lesson4_missingPrevCloseWithholdsTheDay() {
        Portfolio.Snapshot s = Portfolio.build(
                List.of(position("BTC", "0.5", "20000", "0"),
                        position("AAPL", "10", "1800", "0")),
                catalog(),
                Map.of("BTC", quote("50000", null),              // feed gave no reference
                       "AAPL", quote("200", "190")),
                Map.of());

        assertNull(holding(s, "BTC").dayChange(), "no reference price, no claim about today");
        assertNull(holding(s, "BTC").dayChangePct());
        assertNotNull(holding(s, "AAPL").dayChange(), "the one we can compute, we do");
        assertEquals("100.00", holding(s, "AAPL").dayChange().toPlainString());

        Portfolio.Aggregate a = s.aggregate();
        assertEquals(1, a.withoutPrevClose());
        assertNull(a.dayChange(),
                "THE POINT: 100.00 is the day change of PART of this portfolio. Printing it as "
                + "the portfolio's day change would be a true number under a false label");
        // the rest of the screen still works · one missing field is not an outage
        assertEquals("27000.00", a.marketValue().toPlainString());
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 5: a position opened TODAY did not live through today's whole move")
    void lesson5_positionOpenedTodayIsMarkedFromItsFill() {
        // bought all 10 shares today at 195; the prior close was 190, it is now 200
        Portfolio.Snapshot s = Portfolio.build(
                List.of(position("AAPL", "10", "1950", "0")),
                catalog(),
                Map.of("AAPL", quote("200", "190")),
                Map.of("AAPL", flow("10", "1950")));

        Portfolio.Holding h = holding(s, "AAPL");
        // 10 * (200 - 195) = 50 · what we actually made
        assertEquals("50.00", h.dayChange().toPlainString(),
                "THE POINT: the naive qty * (price - prevClose) says 100.00, crediting us with "
                + "a 190->195 move we were not in the market for");
        assertNull(h.dayChangePct(),
                "and there is no percentage: a position that started at zero has no starting value");
        assertEquals("50.00", s.aggregate().dayChange().toPlainString());
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 6: a position that traded today splits · the part you held, and the part you bought")
    void lesson6_partiallyTradedPositionSplits() {
        // held 6 through the close, bought 4 more today at 195
        Portfolio.Snapshot s = Portfolio.build(
                List.of(position("AAPL", "10", "1900", "0")),
                catalog(),
                Map.of("AAPL", quote("200", "190")),
                Map.of("AAPL", flow("4", "780")));

        // held:   6 * (200 - 190) = 60
        // traded: 4 * (200 - 195) = 20
        Portfolio.Holding h = holding(s, "AAPL");
        assertEquals("80.00", h.dayChange().toPlainString(),
                "60 from what you held plus 20 from what you bought · not the naive 100.00");
        // percent is against what you actually had at the close: 6 * 190 = 1140
        assertEquals("7.02", h.dayChangePct().toPlainString());
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 7: an unpriced holding WITHHOLDS the total · a smaller number is not a truer one")
    void lesson7_unpricedHoldingWithholdsTheTotal() {
        Portfolio.Snapshot s = Portfolio.build(
                List.of(position("BTC", "0.5", "20000", "0"),
                        position("AAPL", "10", "1800", "0")),
                catalog(),
                Map.of("AAPL", quote("200", "190")),            // nothing for BTC at all
                Map.of());

        Portfolio.Holding btc = holding(s, "BTC");
        assertNull(btc.price(), "a price we do not have is not zero");
        assertNull(btc.value(), "and so there is no value to show");
        assertNull(btc.unrealized());
        assertEquals("unavailable", btc.priceSource(), "and the row says why, so the UI can grey it out");
        // the cost basis is stored, not marked · it survives the feed going down
        assertEquals("20000.00", btc.costBasis().toPlainString());

        Portfolio.Aggregate a = s.aggregate();
        assertEquals(1, a.unpriced());
        assertNull(a.marketValue(),
                "THE POINT: 2000.00 is what the priced half is worth. Labelling it 'portfolio "
                + "value' turns a 27000 portfolio into a 2000 one on screen");
        assertNull(a.unrealized());
        assertEquals("21800.00", a.costBasis().toPlainString(), "what it cost is still known");
        assertEquals("0.00", a.realized().toPlainString(), "and so is what was realised");
    }

    // ------------------------------------------------------------------ fixtures

    private static Broker.Position position(String symbol, String qty, String basis, String realized) {
        return new Broker.Position(IGOR, symbol, new BigDecimal(qty),
                new BigDecimal(basis), new BigDecimal(realized));
    }

    private static Portfolio.Quote quote(String price, String prevClose) {
        return new Portfolio.Quote(new BigDecimal(price),
                prevClose == null ? null : new BigDecimal(prevClose), "live");
    }

    private static Broker.DayFlow flow(String qty, String notional) {
        return new Broker.DayFlow(new BigDecimal(qty), new BigDecimal(notional));
    }

    private static Map<String, Catalog.Instrument> catalog() {
        return Map.of(
                "BTC", new Catalog.Instrument("BTC", "crypto", "BTC", "EUR", "Bitcoin", "CRYPTO"),
                "AAPL", new Catalog.Instrument("AAPL", "equity", "AAPL", "EUR", "Apple Inc.", "NASDAQ.NMS"));
    }

    private static Portfolio.Holding holding(Portfolio.Snapshot s, String symbol) {
        return s.holdings().stream().filter(h -> h.symbol().equals(symbol)).findFirst()
                .orElseThrow(() -> new AssertionError("no holding " + symbol));
    }
}
