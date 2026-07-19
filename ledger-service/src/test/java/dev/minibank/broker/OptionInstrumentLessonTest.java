package dev.minibank.broker;

import dev.minibank.ledger.AssetRegistry;
import dev.minibank.ledger.Directory;
import dev.minibank.ledger.Fixtures;
import dev.minibank.ledger.Products;
import dev.minibank.ledger.Shard;
import dev.minibank.ledger.Shards;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE OPTION INSTRUMENT · one contract is a hundred shares, and a date can end it.
 *
 * The bank could already list a third instrument (AssetRegistry closed the
 * settle-into-apple's-account bug). What it could not do is list one whose
 * unit is not a unit. Every money number in the system was qty * price, which
 * is right for a share and wrong by a factor of a HUNDRED for a contract.
 *
 * The broker's schema had carried a `multiplier` column since V1, commented
 * "100 for an option contract", and nothing had ever read it: not
 * Catalog.Instrument, not Catalog.COLUMNS, not Catalog.put. Every row held the
 * default 1 and the column could have been dropped without changing a number.
 * That is worse than not having it · it reads as support for contracts that
 * does not exist.
 *
 *   lesson 1  an option position values at qty * price * 100, not qty * price
 *   lesson 2  and a STOCK is untouched, because its multiplier is 1 rather
 *             than because anything branches on whether it is an option
 *   lesson 3  the multiplier scales the CASH leg and never the units, which
 *             is what keeps Reconciliation's quantity invariant true
 *   lesson 4  an option nobody can price is UNPRICED · not zero, and the
 *             total is withheld rather than partially summed
 *   lesson 5  an EXPIRED contract is not valued either, and it does not get
 *             to keep its last premium as though that were a current mark
 *   lesson 6  the shelf walk finds an option holding, so relocation moves it
 *   lesson 7  re-listing under a different contract size RAISES rather than
 *             silently restating every position anyone already holds
 *
 * ON WHAT IS ACTUALLY LISTED. No option is seeded in Catalog.seed(). Yahoo
 * does quote them on the same keyless endpoint PriceFeed already uses (an OCC
 * symbol like AAPL260821C00250000 returns instrumentType OPTION with both
 * regularMarketPrice and chartPreviousClose), so this is not a case of the
 * model being unpriceable · it is the ordinary rule that listing an instrument
 * is a business decision and not a test's to make. The model is proven here,
 * on instruments listed by this test, exactly as ThirdInstrumentLessonTest
 * proves MSFT without seeding it.
 *
 * Requires: docker compose up -d   (shards :5434/:5435, control :5433)
 */
class OptionInstrumentLessonTest {

    static final long IGOR = 10;
    static final int EU = 0, UK = 1;

    /**
     * An OCC-style contract symbol: AAPL, expiring 2026-08-21, Call, strike
     * 250.000. It is listed by this test and by nothing else.
     *
     * OCC symbols need no special handling anywhere: AssetRegistry.normalize
     * only uppercases, and derivedSlot is FNV-1a over the bytes, so a 19
     * character symbol hashes exactly as happily as a 4 character one.
     */
    static final String CALL = "AAPL260821C00250000";
    static final LocalDate EXPIRY = LocalDate.of(2026, 8, 21);

    /** One contract controls a hundred shares. The whole lesson, as a number. */
    static final BigDecimal HUNDRED = new BigDecimal("100");

    /** Fixed, so that "has it expired" is decided by the argument and not by
     *  the day the suite happens to run. Comfortably before EXPIRY. */
    static final LocalDate TODAY = LocalDate.of(2026, 7, 19);

    @BeforeAll
    static void boot() throws Exception {
        Shards.boot(
                System.getenv().getOrDefault("MINIBANK_SHARD0_URL", "jdbc:postgresql://localhost:5434/minibank"),
                System.getenv().getOrDefault("MINIBANK_SHARD1_URL", "jdbc:postgresql://localhost:5435/minibank"),
                "minibank", "minibank", 5);
        for (Shard s : Shards.all()) s.createSchema();
        Directory.createOwnDatabase();
        Shards.nameRegions("eu", "uk");
        Shards.setResolver(Directory::shardOf);
    }

    @AfterAll
    static void unplug() {
        Shards.setResolver(null);
    }

    // ------------------------------------------------------------------
    // lessons 1-5 are PURE · Portfolio opens no connection and calls no feed,
    // so the arithmetic is asserted against numbers chosen here rather than
    // against whatever an option happened to cost while the suite ran
    // ------------------------------------------------------------------

    @Test
    @DisplayName("lesson 1: an option values at qty * price * 100 · the contract size is not decoration")
    void lesson1_optionValuesAtQtyTimesPriceTimesMultiplier() {
        // two contracts, premium 5.00 each, so 2 * 5.00 * 100 = 1000.00
        Portfolio.Snapshot s = Portfolio.build(
                List.of(position(CALL, "2", "900.00", "0")),
                Map.of(CALL, option(HUNDRED, EXPIRY)),
                Map.of(CALL, quote("5.00", "4.50")),
                Map.of(), TODAY);

        Portfolio.Holding h = holding(s, CALL);
        assertEquals("1000.00", h.value().toPlainString(),
                "THE POINT: qty * price alone says 10.00, which prices a thousand euro of "
                + "exposure at the cost of a sandwich · it is wrong by exactly the multiplier");
        assertEquals("1000.00", s.aggregate().marketValue().toPlainString(),
                "and the total carries the same number the row does");

        // unrealized is money minus money · the basis was already accumulated
        // through Broker.consideration, which applied the multiplier at fill
        // time, so it must NOT be scaled a second time here
        assertEquals("100.00", h.unrealized().toPlainString(),
                "1000.00 of value against 900.00 of basis · a multiplier applied twice would say 99100.00");

        // 2 * (5.00 - 4.50) * 100
        assertEquals("100.00", h.dayChange().toPlainString(),
                "the day move is scaled by the contract size too, or the headline "
                + "disagrees with the value it sits above");

        assertEquals(0, HUNDRED.compareTo(h.multiplier()),
                "and the row carries the contract size, so a screen can say why value is not qty * price");
    }

    @Test
    @DisplayName("lesson 2: a stock is UNAFFECTED · its multiplier is 1, and that is not a special case")
    void lesson2_stockIsUnaffected() {
        Portfolio.Snapshot s = Portfolio.build(
                List.of(position("AAPL", "10", "1800.00", "0")),
                Map.of("AAPL", stock()),
                Map.of("AAPL", quote("200.00", "190.00")),
                Map.of(), TODAY);

        Portfolio.Holding h = holding(s, "AAPL");
        assertEquals("2000.00", h.value().toPlainString(),
                "10 shares at 200 is 2000, exactly as it was before contracts existed");
        assertEquals("200.00", h.unrealized().toPlainString(), "2000 against a 1800 basis");
        assertEquals("100.00", h.dayChange().toPlainString(), "10 * (200 - 190)");
        assertEquals("2000.00", s.aggregate().marketValue().toPlainString(), "and the total agrees");

        // The multiplication runs for a share too · there is no `if (isOption)`
        // anywhere. A stock is the ordinary case with the ordinary contract
        // size, so the same line of arithmetic serves both and there is no
        // second path to keep in step with the first.
        assertEquals(0, BigDecimal.ONE.compareTo(h.multiplier()),
                "one share is one share, stated rather than assumed");
    }

    @Test
    @DisplayName("lesson 3: the multiplier scales the CASH leg and never the units")
    void lesson3_multiplierIsOnTheMoneyNotTheQuantity() {
        // two contracts at 5.00 with a 0.10 commission
        BigDecimal cash = Broker.consideration("buy", new BigDecimal("2"), new BigDecimal("5.00"),
                new BigDecimal("0.10"), HUNDRED);
        assertEquals("1000.10", cash.toPlainString(),
                "2 * 5.00 * 100, plus the fee · without the multiplier the customer is "
                + "charged 10.10 for a thousand euro of exposure");

        assertEquals("10.10", Broker.consideration("buy", new BigDecimal("2"), new BigDecimal("5.00"),
                        new BigDecimal("0.10"), BigDecimal.ONE).toPlainString(),
                "and a multiplier of one is the old behaviour exactly, so shares did not move");

        // WHY THE UNITS ARE LEFT ALONE. Reconciliation asserts that the
        // ledger's asset balance equals the broker's position quantity minus
        // what is in flight. The ledger therefore holds CONTRACTS, not the
        // shares they control · scaling the quantity leg would report a 100x
        // divergence on every option position the bank ever settled.
        Broker.Position p = Broker.advance(
                new Broker.Position(IGOR, CALL, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                "buy", new BigDecimal("2"), new BigDecimal("5.00"), new BigDecimal("0.10"), HUNDRED);
        assertEquals(0, new BigDecimal("2").compareTo(p.qty()),
                "the position holds TWO contracts, not two hundred shares");
        assertEquals("1000.10", p.costBasis().toPlainString(),
                "while the basis is the money that actually left the account");

        // and the contract size is not optional: there is no four-argument
        // overload, so "I forgot it" cannot silently mean "it is one"
        assertThrows(IllegalArgumentException.class,
                () -> Broker.consideration("buy", new BigDecimal("2"), new BigDecimal("5.00"),
                        new BigDecimal("0.10"), BigDecimal.ZERO),
                "a zero contract size settles every fill for nothing · refuse it");
    }

    @Test
    @DisplayName("lesson 4: an option nobody can price is UNPRICED · not zero, and the total is withheld")
    void lesson4_unpriceableOptionIsUnpricedNotZero() {
        Portfolio.Snapshot s = Portfolio.build(
                List.of(position(CALL, "2", "900.00", "0"),
                        position("AAPL", "10", "1800.00", "0")),
                Map.of(CALL, option(HUNDRED, EXPIRY), "AAPL", stock()),
                Map.of("AAPL", quote("200.00", "190.00")),   // nothing at all for the contract
                Map.of(), TODAY);

        Portfolio.Holding h = holding(s, CALL);
        assertNull(h.value(),
                "THE POINT: zero is a PRICE, and a wrong one · it renders as a total loss "
                + "on a contract that may be deep in the money");
        assertNull(h.price(), "and no mark is invented to stand in for the one we do not have");
        assertNull(h.unrealized(), "an unknown value yields an unknown gain, not a 900 loss");
        assertEquals(1, s.aggregate().unpriced(), "the row is counted so the screen can explain itself");

        assertNull(s.aggregate().marketValue(),
                "and the TOTAL is withheld · 2000.00 is the value of the stock, not of the portfolio, "
                + "and a partial sum renders identically to a complete one");

        // the row is still drawn · you hold it, and a holding that vanishes
        // from the screen because a feed was down is its own kind of lie
        assertEquals(2, s.holdings().size(), "both are held, so both are drawn");
    }

    @Test
    @DisplayName("lesson 5: an EXPIRED contract is not valued, and does not keep its last premium")
    void lesson5_expiredContractIsNotALivePosition() {
        LocalDate afterExpiry = EXPIRY.plusDays(1);

        // the feed still answers · this is the dangerous case, because there
        // is nothing missing to notice
        Portfolio.Snapshot s = Portfolio.build(
                List.of(position(CALL, "2", "900.00", "0")),
                Map.of(CALL, option(HUNDRED, EXPIRY)),
                Map.of(CALL, quote("5.00", "4.50")),
                Map.of(), afterExpiry);

        Portfolio.Holding h = holding(s, CALL);
        assertNull(h.value(),
                "an expired contract is worth what it SETTLED for, and nobody here observed that · "
                + "1000.00 states it is still live and 0.00 states it expired worthless, "
                + "and we can prove neither");
        assertNull(h.price(),
                "the last premium is not a current price · showing it in a Last column claims "
                + "it is the price of something the customer still holds");
        assertNull(h.dayChange(), "a contract that no longer trades did not move today");
        assertEquals("expired", h.priceSource(),
                "and the state is NAMED · 'unavailable' would say the feed is down and "
                + "imply the number is coming back");

        assertEquals(1, s.aggregate().expired(), "counted, so the screen can say why the total is missing");
        assertNull(s.aggregate().marketValue(),
                "and the total is withheld exactly as it is for an unpriced row · the position is "
                + "held and unvalued, so the sum of the others is not the portfolio");

        // the boundary itself: a contract trades ON its expiry day
        Portfolio.Snapshot onTheDay = Portfolio.build(
                List.of(position(CALL, "2", "900.00", "0")),
                Map.of(CALL, option(HUNDRED, EXPIRY)),
                Map.of(CALL, quote("5.00", "4.50")),
                Map.of(), EXPIRY);
        assertEquals("1000.00", holding(onTheDay, CALL).value().toPlainString(),
                "it expires AFTER its expiry date, not on it · retiring it a day early "
                + "would withhold a total that was still knowable");
    }

    // ------------------------------------------------------------------
    // lessons 6-7 touch the database
    // ------------------------------------------------------------------

    @BeforeEach
    void freshWorld() throws Exception {
        Fixtures.resetShards();
        Fixtures.resetDirectory();
        Directory.register(IGOR, "igor", EU);
        Shards.forCustomer(IGOR).createCustomer(IGOR, "igor");
        Products.ensureFor(IGOR);
    }

    @Test
    @DisplayName("lesson 6: the shelf walk FINDS an option holding · so relocation can move it")
    void lesson6_shelfWalkIncludesAnOptionHolding() throws Exception {
        AssetRegistry.register(CALL, CALL, "aapl aug 2026 250 call", HUNDRED, "option", EXPIRY);

        Shard home = Shards.forCustomer(IGOR);
        long holdingId;
        try (Connection c = home.open()) {
            holdingId = AssetRegistry.ensureHolding(c, CALL, IGOR);
        }

        List<Products.ShelfAccount> shelf = Products.shelfAccounts(home, IGOR);

        // WHY THIS MATTERS. An option holding sits at ASSET_BASE + slot*STRIDE
        // + customer · a billion away from the product shelf's customerId+100..600
        // range. A walk built on OFFSETS alone would find the customer's
        // savings and card and walk straight past a contract worth more than
        // both, stranding it on the old shard during a relocation. The walk
        // reads the registry, and this asserts that it still does.
        assertTrue(shelf.stream().anyMatch(a -> a.id() == holdingId && CALL.equals(a.symbol())),
                "the contract's holding account is on the shelf · a relocation that misses it "
                + "leaves the position behind on the old shard");

        Products.ShelfAccount found = shelf.stream()
                .filter(a -> a.id() == holdingId).findFirst().orElseThrow();
        assertEquals(CALL, found.currency(),
                "and it is denominated in the contract's own ledger currency, not in EUR");

        // the sum-zero invariant is per-currency and the contract has its own
        // currency, so an option holding is an asset account like any other
        assertNotNull(found.label(), "it carries the label the registry recorded");
    }

    @Test
    @DisplayName("lesson 7: re-listing under a DIFFERENT contract size raises · it does not restate holdings")
    void lesson7_contractSizeCannotBeChangedUnderExistingHoldings() throws Exception {
        AssetRegistry.register(CALL, CALL, "aapl aug 2026 250 call", HUNDRED, "option", EXPIRY);

        // idempotent about repetition · this is what makes it safe on boot
        AssetRegistry.register(CALL, CALL, "aapl aug 2026 250 call", HUNDRED, "option", EXPIRY);

        IllegalStateException boom = assertThrows(IllegalStateException.class,
                () -> AssetRegistry.register(CALL, CALL, "aapl aug 2026 250 call",
                        new BigDecimal("10"), "option", EXPIRY),
                "THE POINT: changing the contract size restates every existing position by the "
                + "ratio, and the balances do not move · so the books still sum to zero in every "
                + "currency and only the VALUATION is wrong. That is the exact shape of bug the "
                + "asset registry exists to delete: wrong, and passing every check the bank runs.");
        assertTrue(boom.getMessage().contains("multiplier"),
                "and it says what contradicted, not merely that something did");

        // an expiry on something that does not expire is refused for the same
        // reason: it would retire a holding that nothing should ever retire
        assertThrows(IllegalArgumentException.class,
                () -> AssetRegistry.register("MSFT", "MSFT", "microsoft",
                        BigDecimal.ONE, "equity", EXPIRY),
                "a share does not have an expiry date");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static Broker.Position position(String symbol, String qty, String basis, String realized) {
        return new Broker.Position(IGOR, symbol, new BigDecimal(qty),
                new BigDecimal(basis), new BigDecimal(realized));
    }

    private static Portfolio.Quote quote(String price, String prevClose) {
        return new Portfolio.Quote(new BigDecimal(price),
                prevClose == null ? null : new BigDecimal(prevClose), "live");
    }

    private static Catalog.Instrument option(BigDecimal multiplier, LocalDate expiry) {
        return new Catalog.Instrument(CALL, "option", CALL, "EUR",
                "AAPL Aug 2026 250 call", "OPR", multiplier, expiry);
    }

    private static Catalog.Instrument stock() {
        return new Catalog.Instrument("AAPL", "equity", "AAPL", "EUR",
                "Apple Inc.", "NASDAQ.NMS", BigDecimal.ONE, null);
    }

    private static Portfolio.Holding holding(Portfolio.Snapshot s, String symbol) {
        return s.holdings().stream().filter(h -> h.symbol().equals(symbol)).findFirst().orElseThrow();
    }
}
