package dev.minibank.broker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE PORTFOLIO, BANDED BY ASSET CLASS · and what a subtotal is allowed to say.
 *
 * A subtotal is a second place for the same arithmetic to be wrong in, and the
 * cheap way to build one is to let the screen reduce the rows it already has.
 * That is the mistake these lessons exist to prevent. Reducing on the client
 * sums a column of ALREADY-ROUNDED cents, so the bands and the headline
 * disagree in the last decimal for large books; and it puts rule 3 · an
 * incomplete total is not a total · into JavaScript, where it has to be written
 * a second time and will eventually be written differently. So the groups are
 * accumulated in Portfolio.build alongside the aggregate, through the same
 * type, with the same rules, from the same unrounded numbers.
 *
 *   lesson 1  holdings band by kind, in reading order
 *   lesson 2  a kind with no holdings has no band · not an empty one
 *   lesson 3  the bands sum to the headline
 *   lesson 4  one unpriced stock withholds the STOCKS subtotal, and only that one
 *   lesson 5  the day has a percentage, and it is withheld with the day itself
 *   lesson 6  a book opened entirely today has a day change and no percentage
 *   lesson 7  a closed position is in no band, and still in the headline
 *   lesson 8  an unknown kind gets its own band rather than joining the stocks
 *   lesson 9  the page reads the bands rather than reducing the rows itself
 *
 * Pure, like the rest of Portfolio: no database, no feed, no clock.
 */
class PortfolioGroupingLessonTest {

    static final long IGOR = 10;

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 1: holdings band by kind · Stocks, Options, Crypto, in that order")
    void lesson1_holdingsBandByKind() {
        Portfolio.Snapshot s = Portfolio.build(
                // deliberately supplied crypto-first, so passing requires the
                // ORDER to come from KIND_ORDER and not from the input
                List.of(position("BTC", "0.5", "20000", "0"),
                        position("AAPL", "10", "1800", "0"),
                        position("AAPL260821C00250000", "2", "16000", "0")),
                catalog(),
                Map.of("BTC", quote("50000", "49000"),
                       "AAPL", quote("200", "190"),
                       "AAPL260821C00250000", quote("85", "83")),
                Map.of(), TODAY);

        assertEquals(List.of("equity", "option", "crypto"),
                s.groups().stream().map(Portfolio.Group::kind).toList(),
                "the reading order is the screen's, not the query's");
        assertEquals(List.of("Stocks", "Options", "Crypto"),
                s.groups().stream().map(Portfolio.Group::label).toList(),
                "and each band is named for a customer, not for a column in `instruments`");
        for (Portfolio.Group g : s.groups())
            assertEquals(1, g.holdings(), g.label() + " has exactly its own row");
    }

    // ------------------------------------------------------------------
    /**
     * A band with a zero subtotal is a report about a database. The screen
     * would have to apologise for it · "Options €0.00" states that the
     * customer has options worth nothing, which is a different fact from
     * having none.
     */
    @Test
    @DisplayName("lesson 2: a kind with no holdings has no band at all")
    void lesson2_anEmptyKindHasNoBand() {
        Portfolio.Snapshot s = Portfolio.build(
                List.of(position("AAPL", "10", "1800", "0")),
                catalog(),
                Map.of("AAPL", quote("200", "190")),
                Map.of(), TODAY);

        assertEquals(1, s.groups().size(), "one holding, one band");
        assertEquals("equity", s.groups().get(0).kind());
        assertTrue(s.groups().stream().noneMatch(g -> "option".equals(g.kind())),
                "Options is in KIND_ORDER but nothing is held · so there is no Options band");
        assertTrue(s.groups().stream().noneMatch(g -> "crypto".equals(g.kind())),
                "and no Crypto band either");
    }

    // ------------------------------------------------------------------
    /**
     * The property a reader will actually check with a calculator.
     */
    @Test
    @DisplayName("lesson 3: the band subtotals sum to the headline")
    void lesson3_bandsSumToTheHeadline() {
        Portfolio.Snapshot s = Portfolio.build(
                List.of(position("BTC", "0.5", "20000", "0"),
                        position("AAPL", "10", "1800", "0")),
                catalog(),
                Map.of("BTC", quote("50000", "49000"),
                       "AAPL", quote("200", "190")),
                Map.of(), TODAY);

        // 0.5 * 50000 = 25000 crypto, 10 * 200 = 2000 equity
        assertEquals("2000.00", group(s, "equity").marketValue().toPlainString());
        assertEquals("25000.00", group(s, "crypto").marketValue().toPlainString());
        assertEquals("27000.00", s.aggregate().marketValue().toPlainString());

        BigDecimal value = BigDecimal.ZERO, unreal = BigDecimal.ZERO, day = BigDecimal.ZERO;
        for (Portfolio.Group g : s.groups()) {
            value  = value.add(g.marketValue());
            unreal = unreal.add(g.unrealized());
            day    = day.add(g.dayChange());
        }
        assertEquals(0, value.compareTo(s.aggregate().marketValue()), "values add up");
        assertEquals(0, unreal.compareTo(s.aggregate().unrealized()), "unrealised adds up");
        assertEquals(0, day.compareTo(s.aggregate().dayChange()), "the day adds up");
    }

    // ------------------------------------------------------------------
    /**
     * Rule 3 at band scope, and the reason bands are worth having: an
     * incomplete total is not a total, but it is only ITS OWN total that it
     * spoils. Before bands, one unpriced holding blanked the single figure on
     * the page and the customer learned nothing about the rest of their book.
     */
    @Test
    @DisplayName("lesson 4: one unpriced stock withholds the Stocks subtotal · and only that one")
    void lesson4_anUnpricedRowWithholdsItsOwnBandOnly() {
        Portfolio.Snapshot s = Portfolio.build(
                List.of(position("BTC", "0.5", "20000", "0"),
                        position("AAPL", "10", "1800", "0")),
                catalog(),
                Map.of("BTC", quote("50000", "49000"),
                       "AAPL", Portfolio.Quote.none()),          // the feed had nothing
                Map.of(), TODAY);

        Portfolio.Group stocks = group(s, "equity");
        assertNull(stocks.marketValue(),
                "the Stocks subtotal is withheld · a band of one unpriced row has no value to state");
        assertNull(stocks.unrealized(), "and so is its P&L");
        assertNull(stocks.unrealizedPct(), "and its percentage");
        assertEquals(1, stocks.unpriced(), "the band says why, so the screen need not guess");

        Portfolio.Group crypto = group(s, "crypto");
        assertNotNull(crypto.marketValue(),
                "THE CRYPTO SUBTOTAL STILL STANDS · bitcoin was priced, and a stock nobody could "
                + "mark is not a reason to withhold what we do know about a different asset class");
        assertEquals("25000.00", crypto.marketValue().toPlainString());

        assertNull(s.aggregate().marketValue(),
                "the headline is still withheld · it covers the unpriced row, so it is incomplete");
    }

    // ------------------------------------------------------------------
    /**
     * The screen has read `a.dayChangePct` since it was written and the field
     * has never existed, so the header's day percentage has never once
     * rendered. This is the test that makes the absence loud.
     */
    @Test
    @DisplayName("lesson 5: the day has a percentage · withheld exactly when the day itself is")
    void lesson5_theDayHasAPercentage() {
        Portfolio.Snapshot with = Portfolio.build(
                List.of(position("AAPL", "10", "1800", "0")),
                catalog(),
                Map.of("AAPL", quote("200", "190")),
                Map.of(), TODAY);

        // 10 shares moved 190 -> 200, so +100 on a prior close worth 1900
        assertEquals("100.00", with.aggregate().dayChange().toPlainString());
        assertNotNull(with.aggregate().dayChangePct(),
                "the day change has an honest denominator: what the book was worth at the prior close");
        assertEquals("5.26", with.aggregate().dayChangePct().toPlainString(), "100 / 1900");
        assertEquals("5.26", group(with, "equity").dayChangePct().toPlainString(),
                "and the band says the same thing · one book, one asset class");

        Portfolio.Snapshot without = Portfolio.build(
                List.of(position("AAPL", "10", "1800", "0")),
                catalog(),
                Map.of("AAPL", quote("200", null)),              // no prior close
                Map.of(), TODAY);

        assertNull(without.aggregate().dayChange(), "no prior close, no day");
        assertNull(without.aggregate().dayChangePct(),
                "AND NO PERCENTAGE EITHER · a percentage of a withheld number is not a smaller "
                + "claim than the number, it is the same claim wearing a percent sign");
    }

    // ------------------------------------------------------------------
    /**
     * The one case where the currency figure is real and the percentage is
     * not. Printing 0.00% here would be the same fabrication as a zero price:
     * a specific claim ("it did not move") standing in for "we have nothing to
     * divide by".
     */
    @Test
    @DisplayName("lesson 6: a book opened entirely today has a day change and no percentage")
    void lesson6_openedTodayHasNoDenominator() {
        Portfolio.Snapshot s = Portfolio.build(
                // held 10, and all 10 were bought today for 1800 · so nothing
                // was held at the prior close
                List.of(position("AAPL", "10", "1800", "0")),
                catalog(),
                Map.of("AAPL", quote("200", "190")),
                Map.of("AAPL", flow("10", "1800")), TODAY);

        // bought for 1800, now worth 2000 · a real +200 earned today
        assertEquals("200.00", s.aggregate().dayChange().toPlainString(),
                "the money moved and the currency figure is real");
        assertNull(s.aggregate().dayChangePct(),
                "NOT 0.00% · the book was worth nothing at the prior close, so there is no "
                + "denominator, and 0.00% would claim it did not move when it moved 200 euro");
        assertNull(group(s, "equity").dayChangePct(), "the band withholds it for the same reason");
        assertNull(holding(s, "AAPL").dayChangePct(), "and so does the row");
        assertEquals("200.00", holding(s, "AAPL").dayChange().toPlainString(),
                "the row keeps its currency figure too");
    }

    // ------------------------------------------------------------------
    /**
     * The honest gap, asserted rather than left to be discovered. A band is
     * the rows drawn under it; a position closed to flat has no row. Its
     * realised P&L is money that already moved, so it stays in the headline,
     * and the headline is therefore allowed to exceed the sum of the bands.
     */
    @Test
    @DisplayName("lesson 7: a closed position is in no band, and still in the headline")
    void lesson7_aClosedPositionIsInNoBand() {
        Portfolio.Snapshot s = Portfolio.build(
                List.of(position("AAPL", "10", "1800", "0"),
                        position("BTC", "0", "0", "500")),       // sold out, kept the gain
                catalog(),
                Map.of("AAPL", quote("200", "190")),
                Map.of(), TODAY);

        assertEquals(1, s.groups().size(), "one band, because there is one holding");
        assertEquals("equity", s.groups().get(0).kind());
        assertTrue(s.groups().stream().noneMatch(g -> "crypto".equals(g.kind())),
                "NO EMPTY CRYPTO BAND for a position that is no longer held");

        assertEquals("500.00", s.aggregate().realized().toPlainString(),
                "the realised gain is money that already moved · it does not leave the headline "
                + "just because the row it came from is gone");
        assertEquals(1, s.aggregate().closedPositions(),
                "and the count is what lets the screen account for the difference between "
                + "the headline and the sum of the bands");
    }

    // ------------------------------------------------------------------
    /**
     * The property that stops the NEXT asset class from being a UI change.
     * KIND_ORDER is an ordering, not a whitelist.
     */
    @Test
    @DisplayName("lesson 8: an unknown kind gets its own band rather than joining the stocks")
    void lesson8_anUnknownKindGetsItsOwnBand() {
        Map<String, Catalog.Instrument> cat = new java.util.HashMap<>(catalog());
        cat.put("VWCE", new Catalog.Instrument("VWCE", "fund", "VWCE", "EUR",
                "Vanguard FTSE All-World", "XETRA", BigDecimal.ONE, null));

        Portfolio.Snapshot s = Portfolio.build(
                List.of(position("AAPL", "10", "1800", "0"),
                        position("VWCE", "5", "500", "0")),
                cat,
                Map.of("AAPL", quote("200", "190"),
                       "VWCE", quote("120", "118")),
                Map.of(), TODAY);

        assertEquals(2, s.groups().size(), "two kinds, two bands");
        assertEquals("fund", s.groups().get(1).kind(),
                "an unnamed kind is appended after the named ones, not dropped");
        assertEquals("Fund", s.groups().get(1).label(),
                "and it is labelled from its own kind rather than left blank");
        assertEquals("600.00", group(s, "fund").marketValue().toPlainString(),
                "5 * 120 · valued like anything else");
        assertEquals("2000.00", group(s, "equity").marketValue().toPlainString(),
                "AND THE STOCKS SUBTOTAL IS UNPOLLUTED · lumping an unrecognised kind in with "
                + "the equities would make this number wrong by the size of the fund");
    }

    // ------------------------------------------------------------------
    /**
     * STRUCTURAL. The bands are shipped precisely so the page does not reduce
     * the rows itself, and nothing else can prove the page took them. A
     * client-side reduce would sum rounded cents and would have to restate
     * rule 3 in JavaScript.
     */
    @Test
    @DisplayName("lesson 9: the page reads the shipped bands rather than reducing the rows")
    void lesson9_thePageReadsTheShippedBands() throws Exception {
        String page = resource("/web-broker/portfolio.html");

        int fn = page.indexOf("function drawHoldings");
        assertTrue(fn >= 0, "drawHoldings still exists");
        int next = page.indexOf("\nfunction ", fn + 1);
        String drawHoldings = page.substring(fn, next < 0 ? page.length() : next);

        // \b so that p.groupsById or a local named groupsFoo cannot pass for
        // the shipped field · a substring match would let a rename keep this
        // green, which is how a test stops proving anything
        assertTrue(java.util.regex.Pattern.compile("\\bp\\.groups\\b").matcher(drawHoldings).find(),
                "drawHoldings must read the SHIPPED groups. Read:\n" + drawHoldings);

        // and the subtotals must be the shipped ones, not re-derived
        assertTrue(java.util.regex.Pattern.compile("\\bg\\.marketValue\\b").matcher(page).find(),
                "the band draws the subtotal the server computed");
        assertTrue(java.util.regex.Pattern.compile("\\bg\\.dayChangePct\\b").matcher(page).find(),
                "including the day percentage, which only the server can withhold correctly");
        assertTrue(java.util.regex.Pattern.compile("\\bg\\.unrealizedPct\\b").matcher(page).find(),
                "and the unrealised percentage");

        // the headline percentage the page has always read and never received
        int hdr = page.indexOf("function drawHeader");
        int hdrEnd = page.indexOf("\nfunction ", hdr + 1);
        String drawHeader = page.substring(hdr, hdrEnd < 0 ? page.length() : hdrEnd);
        assertTrue(java.util.regex.Pattern.compile("\\ba\\.dayChangePct\\b").matcher(drawHeader).find(),
                "the header reads the day percentage · BrokerApi now actually ships it");

        // nobody reduces a money column in the browser
        assertTrue(!page.contains(".reduce("),
                "NO CLIENT-SIDE REDUCE over money. Totals are summed unrounded in Portfolio.java "
                + "and rounded once; a reduce here would sum a column of rounded cents and would "
                + "have to re-implement the withholding rule in JavaScript.");
    }

    // ------------------------------------------------------------------
    /**
     * THE ONE CENT, pinned deliberately.
     *
     * Found in a browser, not in a spreadsheet: two live bands read 249.88 and
     * 119.87 under a headline of 369.74, and 249.88 + 119.87 is 369.75. Every
     * one of those three numbers is right. The book was truly worth
     * 369.740581, the bands truly 249.875058 and 119.865524, and each is
     * rounded once from its own unrounded sum · which is the ONLY way each can
     * be the best two-decimal statement of itself.
     *
     * The cent exists solely because adding the displayed figures adds numbers
     * that have already been rounded. Making it go away means defining the
     * headline as the sum of the bands, which drifts the single number the
     * customer actually reads further from the truth with every band added,
     * and is the exact practice PortfolioLessonTest.lesson1 exists to forbid.
     *
     * So this test asserts the discrepancy rather than the absence of one. If
     * someone later "fixes" the reconciliation, this fails and tells them why.
     */
    @Test
    @DisplayName("lesson 10: each figure is rounded once from its own true value · so the bands need not sum to the headline")
    void lesson10_eachFigureIsRoundedOnceFromItsOwnTruth() {
        Portfolio.Snapshot s = Portfolio.build(
                List.of(position("AAPL", "1", "200", "0"),
                        position("BTC", "1", "100", "0")),
                catalog(),
                // 249.875 and 119.865 · each rounds UP on its own, and their
                // true sum 369.740 rounds DOWN
                Map.of("AAPL", quote("249.875", "249.875"),
                       "BTC", quote("119.865", "119.865")),
                Map.of(), TODAY);

        assertEquals("249.88", group(s, "equity").marketValue().toPlainString(),
                "the Stocks band is the best two-decimal statement of 249.875");
        assertEquals("119.87", group(s, "crypto").marketValue().toPlainString(),
                "and the Crypto band of 119.865");
        assertEquals("369.74", s.aggregate().marketValue().toPlainString(),
                "AND THE HEADLINE IS THE BEST STATEMENT OF 369.740 · not 369.75, which is what "
                + "you get by adding two numbers that have already been rounded. If this line "
                + "ever reads 369.75 the headline has been redefined as the sum of the bands, "
                + "and the number the customer actually reads is now the sum of a column of "
                + "rounded cents · see PortfolioLessonTest.lesson1.");

        BigDecimal summed = s.groups().stream().map(Portfolio.Group::marketValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals("369.75", summed.toPlainString(),
                "the bands DO sum to a different number, and that is the correct outcome "
                + "rather than the bug · documented so nobody reconciles it away");
    }

    // ------------------------------------------------------------------ fixtures

    private static Portfolio.Group group(Portfolio.Snapshot s, String kind) {
        return s.groups().stream().filter(g -> kind.equals(g.kind())).findFirst()
                .orElseThrow(() -> new AssertionError("no group " + kind + " in "
                        + s.groups().stream().map(Portfolio.Group::kind).toList()));
    }

    private static Portfolio.Holding holding(Portfolio.Snapshot s, String symbol) {
        return s.holdings().stream().filter(h -> h.symbol().equals(symbol)).findFirst()
                .orElseThrow(() -> new AssertionError("no holding " + symbol));
    }

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

    private static final java.time.LocalDate TODAY = java.time.LocalDate.of(2026, 7, 19);

    /**
     * One of each band. The option's multiplier is 100 and its expiry is well
     * clear of TODAY, so it is a live contract and not an expiry lesson · that
     * one is OptionInstrumentLessonTest's.
     */
    private static Map<String, Catalog.Instrument> catalog() {
        return Map.of(
                "BTC", new Catalog.Instrument("BTC", "crypto", "BTC", "EUR", "Bitcoin", "CRYPTO",
                        BigDecimal.ONE, null),
                "AAPL", new Catalog.Instrument("AAPL", "equity", "AAPL", "EUR", "Apple Inc.", "NASDAQ.NMS",
                        BigDecimal.ONE, null),
                "AAPL260821C00250000", new Catalog.Instrument("AAPL260821C00250000", "option",
                        "AAPL260821C00250000", "EUR", "AAPL Aug 21 '26 250 Call", "OPR",
                        new BigDecimal("100"), java.time.LocalDate.of(2026, 8, 21)));
    }

    private static String resource(String path) throws Exception {
        try (InputStream in = PortfolioGroupingLessonTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
