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
 * THE DAY TOTAL, AND WHAT IT IS ALLOWED TO LEAVE OUT · nothing.
 *
 * Portfolio states three honesty rules and the third is the one that keeps
 * getting away: an aggregate over an incomplete set is NULL, not a partial
 * sum. A smaller, wrong number wearing the label of a bigger, right one is
 * worse than a dash, because the dash prompts a question and the number does
 * not.
 *
 * Both lessons here are cases where the machinery for withholding a total
 * existed, was correct, and was simply not reached · a counter not
 * incremented, a field not read. That is the characteristic shape of an
 * honesty bug in a codebase that already cares: not a missing rule, an
 * unenforced one.
 *
 *   lesson 1  a position CLOSED today whose mark is missing withholds the day
 *   lesson 2  a closed position that did not trade today cannot spoil it
 *   lesson 3  the screen says when the total stands on last known prices
 *
 * Pure arithmetic · no database, no feed.
 */
class PortfolioDayHonestyLessonTest {

    private static final long IGOR = 10;

    /** Portfolio.build takes the valuation date rather than reading a clock,
     *  so that expiry is decided by an argument and not by the day the suite
     *  happens to run. Nothing here expires. */
    private static final java.time.LocalDate TODAY = java.time.LocalDate.of(2026, 7, 19);

    // ------------------------------------------------------------------
    /**
     * A customer sells out of a position today at a gain; the feed then goes
     * unavailable for that symbol.
     *
     * The closed-position branch added to dayChange only when the quote was
     * observed AND had a prior close AND something traded. When that guard
     * failed it fell straight to `continue` WITHOUT incrementing
     * withoutPrevClose · so the aggregate, which is gated on that counter
     * being zero, went on rendering a figure. Their intraday gain vanished
     * from the headline and the remainder was presented as their complete day.
     *
     * The open-position path has always counted correctly in its else. The
     * closed one never did, and the two are the same rule.
     */
    @Test
    @DisplayName("lesson 1: a position closed today with no mark WITHHOLDS the day total, it does not drop it")
    void lesson1_closedPositionWithoutAMarkWithholdsTheDay() {
        // flat now, and it made 100 on the way out
        Broker.Position closed = new Broker.Position(IGOR, "AAPL",
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100.00"));
        // sold 5 today for 1000 · signed the way flowsSince signs a sell
        Map<String, Broker.DayFlow> flows = Map.of("AAPL",
                new Broker.DayFlow(new BigDecimal("-5"), new BigDecimal("-1000.00")));

        Portfolio.Snapshot s = Portfolio.build(List.of(closed), Map.of(),
                Map.of("AAPL", Portfolio.Quote.none()), flows, TODAY);

        assertEquals(0, s.holdings().size(), "nothing is held, so nothing is drawn");
        assertEquals(1, s.aggregate().closedPositions(), "but the row is counted");
        assertEquals(0, new BigDecimal("100.00").compareTo(s.aggregate().realized()),
                "realised P&L is stored, not marked · no feed can take it away");
        assertNull(s.aggregate().dayChange(),
                "the day moved and we cannot say by how much · that is a withheld total, "
                + "not a zero and not the rest of the day pretending to be all of it");
        assertTrue(s.aggregate().withoutPrevClose() > 0,
                "and the screen is told WHY it is missing, so it can explain itself");
    }

    // ------------------------------------------------------------------
    /**
     * The other half of the same rule, and the reason the fix is a nested
     * branch rather than an unconditional counter: a position closed on some
     * EARLIER day contributes nothing to today. Counting it as incomplete
     * would withhold every day total for every customer who has ever closed a
     * position, which is a different bug in the opposite direction.
     */
    @Test
    @DisplayName("lesson 2: a position closed on an earlier day cannot withhold today's total")
    void lesson2_positionClosedEarlierDoesNotSpoilToday() {
        Broker.Position closedLongAgo = new Broker.Position(IGOR, "AAPL",
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100.00"));

        Portfolio.Snapshot s = Portfolio.build(List.of(closedLongAgo), Map.of(),
                Map.of("AAPL", Portfolio.Quote.none()), Map.of(), TODAY);   // nothing traded today

        assertNotNull(s.aggregate().dayChange(),
                "it did not trade today, so it owes today nothing and withholds nothing");
        assertEquals(0, BigDecimal.ZERO.compareTo(s.aggregate().dayChange()),
                "an empty day is a real answer: zero");
        assertEquals(0, s.aggregate().withoutPrevClose(),
                "and it must not be counted as a hole in a set it is not in");
    }

    // ------------------------------------------------------------------
    /**
     * `stale` counts marks served from the last price actually observed
     * because the upstream is unreachable. Those are real observations with an
     * old timestamp, so they legitimately sum into a total · but the total
     * then describes a moment that has passed.
     *
     * The rows already badge themselves and the backend has shipped this count
     * all along for exactly this purpose. The 46px balance figure above them
     * read the field nowhere and rendered identically to a live total. A
     * qualifier that appears only on the row and never on the sum is a
     * disclosure the headline reader never sees.
     *
     * STRUCTURAL, and worth saying why: the difference only exists when a
     * live upstream is down, which a unit test cannot arrange for a page that
     * fetches over HTTP. What can be checked is the thing that was actually
     * missing · the field being read at all.
     */
    @Test
    @DisplayName("lesson 3: the portfolio header reads `stale` · a total on old marks says so")
    void lesson3_headerDisclosesStaleMarks() throws Exception {
        String page = resource("/web-broker/portfolio.html");
        int header = page.indexOf("function drawHeader");
        assertTrue(header >= 0, "drawHeader still exists");
        int nextFn = page.indexOf("\nfunction ", header + 1);
        String drawHeader = page.substring(header, nextFn < 0 ? page.length() : nextFn);

        // \b so that a.staleX or a.staleCount cannot pass for a.stale · a
        // substring match here would let a renamed-away field keep this green,
        // which is precisely how a test stops proving anything
        assertTrue(java.util.regex.Pattern.compile("\\ba\\.stale\\b").matcher(drawHeader).find(),
                "BrokerApi ships \"stale\":N as provenance and the HEADER must read it · "
                + "the biggest number on the page cannot be the only one that never "
                + "qualifies itself. Read in drawHeader:\n" + drawHeader);
        // and it has to reach the DOM, not just be computed and dropped
        assertTrue(drawHeader.contains("staleHtml"),
                "the qualifier must actually be rendered, not merely calculated");
    }

    // ------------------------------------------------------------------
    private static String resource(String path) throws Exception {
        try (InputStream in = PortfolioDayHonestyLessonTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
