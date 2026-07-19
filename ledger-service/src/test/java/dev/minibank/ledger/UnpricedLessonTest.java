package dev.minibank.ledger;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a customer is told when the price feed is down.
 *
 * Deleting the fabricated fallback prices was right: a made-up number that
 * looks like a quote is worse than no quote. But price() became nullable and
 * one reader was left unguarded, so an upstream that was merely SLOW took the
 * support agent down with a NullPointerException instead.
 *
 * The interesting part is not the null check. It is what goes in the gap. This
 * string is the PROMPT for an agent that speaks to customers and cannot tell
 * that a number handed to it was invented, so "0" would be stated confidently
 * as a real price. There are three candidate answers and only one is honest.
 */
class UnpricedLessonTest {

    @Test
    void anUnpricedSymbolDoesNotTakeTheAgentDown() {
        // the exact shape PriceFeed returns when the upstream refused
        PriceFeed.Px unpriced = new PriceFeed.Px(null, null, "unavailable");
        assertFalse(unpriced.priced(), "the feed's own record of having no number");

        String rendered = assertDoesNotThrow(() -> SupportAgent.px(unpriced),
                "an upstream being down must not be an outage of the support agent");
        assertNotNull(rendered);
    }

    @Test
    void theGapIsNamedRatherThanFilledWithAZero() {
        String rendered = SupportAgent.px(new PriceFeed.Px(null, null, "unavailable"));

        // The failure this pins is not a crash, it is a confident lie: an agent
        // handed "0" tells the customer their bitcoin is worth nothing.
        assertNotEquals("0", rendered);
        assertFalse(rendered.matches("[0-9.,]+"),
                "anything that parses as a number will be read out as a price");
        assertTrue(rendered.toLowerCase().contains("unavailable"),
                "say the feed is down, because that is what is true");
    }

    @Test
    void arealPriceIsStillJustThePrice() {
        String rendered = SupportAgent.px(
                new PriceFeed.Px(new BigDecimal("64584.00"), new BigDecimal("70000"), "coingecko"));
        assertEquals("64584.00", rendered, "the healthy path is untouched");
    }

    @Test
    void aMissingQuoteObjectIsTreatedAsUnpriced() {
        // get() returning null and get() returning an unpriced Px are the same
        // fact arriving in two shapes, and both used to reach .price().
        assertDoesNotThrow(() -> SupportAgent.px(null));
        assertFalse(SupportAgent.px(null).matches("[0-9.,]+"));
    }
}
