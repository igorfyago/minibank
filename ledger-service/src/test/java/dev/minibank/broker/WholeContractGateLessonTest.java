package dev.minibank.broker;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A CONTRACT IS INDIVISIBLE · the integer gate, both ways.
 *
 * orders.qty is NUMERIC(20,8) CHECK (qty > 0), built for 0.0013 BTC · so
 * 0.37 option contracts passes every database gate end to end, and the venues
 * are no help: SimulatedVenue fills whatever quantity it is handed, and its
 * notional branch MANUFACTURES the fraction (notional / unitCost at scale 8),
 * so "50 euro worth" of a 5.00-premium contract becomes 0.1 contracts with no
 * qty ever passing through a caller's hands. There is no fractional side of
 * an option contract to own: OCC clears whole contracts, and a position of
 * 0.37 of one is a number the venue can neither exercise nor close.
 *
 * The gate sits in Broker.place, after the expiry gate, for the expiry gate's
 * own reasons: it is the one choke point every order passes for EVERY venue
 * (IbkrVenue included), the instrument's kind is already in hand there, and
 * rejecting after the order row commits gives the customer a recorded refusal
 * with a reason rather than a silent drop. The key is the KIND, never the
 * multiplier · crypto stays fractional however the catalog grows.
 *
 *   lesson 1  0.37 contracts is REFUSED, buying and selling · zero fills
 *   lesson 2  a notional-sized option order is refused · the venue's notional
 *             branch would manufacture the fraction, so it is never reached
 *   lesson 3  two contracts fill · whole numbers pass
 *   lesson 4  "1.00000000" fills · SCALE is not fractionality
 *   lesson 5  0.0013 BTC still fills · crypto is untouched, because the gate
 *             keys on kind and bitcoin's is 'crypto'
 *
 * THE VENUE FILLS EVERYTHING IT IS ASKED TO FILL (AlwaysFills) · so if the
 * gate in Broker.place is removed, lessons 1 and 2 fill and FAIL. The gate is
 * what is under test, not the venue's judgement.
 *
 * Requires: docker compose up -d   (control-plane Postgres on :5433)
 */
class WholeContractGateLessonTest {

    static final long IGOR = 10;
    static final BigDecimal HUNDRED = new BigDecimal("100");

    /** An OCC contract listed by this test, in the broker's catalog only ·
     *  what is under test is the order gate, not the ledger. */
    static final String CONTRACT = "XSP270115C00700000";

    static final ExpiredContractTradeGateLessonTest.AlwaysFills VENUE =
            new ExpiredContractTradeGateLessonTest.AlwaysFills();
    static Broker broker;

    @BeforeAll
    static void boot() throws Exception {
        BrokerDb.createOwnDatabase();
        broker = new Broker(VENUE);
    }

    @BeforeEach
    void freshBook() throws Exception {
        dev.minibank.ledger.Fixtures.resetBrokerDb();
        Catalog.seed();
        listOption(CONTRACT, LocalDate.now(ZoneOffset.UTC).plusYears(1));
        VENUE.reset();
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 1: 0.37 contracts is refused, both ways · and no fill is written")
    void lesson1_fractionalContractRefused() throws Exception {
        Broker.Order buy = broker.place("frac-buy", IGOR, CONTRACT, "buy",
                new BigDecimal("0.37"), null, "market", null);
        assertEquals("rejected", buy.status(),
                "THE POINT: the schema allows 0.37 (NUMERIC built for crypto) and the venue would "
                        + "fill it · only this gate stands between a customer and a position no "
                        + "clearing house recognises");
        assertNotNull(buy.rejectReason());
        assertTrue(buy.rejectReason().contains("whole contracts"), buy.rejectReason());

        Broker.Order sell = broker.place("frac-sell", IGOR, CONTRACT, "sell",
                new BigDecimal("0.5"), null, "market", null);
        assertEquals("rejected", sell.status(), "selling half a contract is the same impossibility");

        assertEquals(0, VENUE.placements, "the venue is never consulted about an impossible quantity");
        assertEquals(0, fillCount(), "no fill, so nothing to settle");
        assertEquals(0, Broker.positions(IGOR).size(), "and no position");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 2: a notional-sized option order is refused · the fraction the venue would manufacture never exists")
    void lesson2_notionalSizingRefused() throws Exception {
        Broker.Order o = broker.place("notional-opt", IGOR, CONTRACT, "buy",
                null, new BigDecimal("50.00"), "market", null);
        assertEquals("rejected", o.status(),
                "THE POINT: SimulatedVenue's notional branch divides money by unit cost at scale 8 "
                        + "and DOWN · 50 euro of a 500-euro contract is 0.1 contracts, manufactured "
                        + "inside the venue with no qty ever passing any gate. So notional sizing "
                        + "for options is refused at the choke point instead.");
        assertTrue(o.rejectReason().contains("whole contracts"), o.rejectReason());
        assertEquals(0, VENUE.placements, "refused before the venue, where the fraction would be born");
        assertEquals(0, fillCount());
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 3: two contracts fill · whole numbers pass the gate they define")
    void lesson3_wholeContractsFill() throws Exception {
        Broker.Order o = broker.place("whole-2", IGOR, CONTRACT, "buy",
                new BigDecimal("2"), null, "market", null);
        assertEquals("filled", o.status());
        assertEquals(1, VENUE.placements);
        assertEquals(1, Broker.positions(IGOR).size());
        assertEquals(0, new BigDecimal("2").compareTo(Broker.positions(IGOR).get(0).qty()));
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 4: \"1.00000000\" fills · scale is not fractionality")
    void lesson4_scaleIsNotFractionality() throws Exception {
        Broker.Order o = broker.place("whole-scaled", IGOR, CONTRACT, "buy",
                new BigDecimal("1.00000000"), null, "market", null);
        assertEquals("filled", o.status(),
                "a whole number at NUMERIC(20,8)'s scale is still a whole number · the gate asks "
                        + "about the remainder, not the representation");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 5: 0.0013 BTC still fills · crypto is untouched because the key is the KIND")
    void lesson5_cryptoStaysFractional() throws Exception {
        Broker.Order o = broker.place("frac-btc", IGOR, "BTC", "buy",
                new BigDecimal("0.0013"), null, "market", null);
        assertEquals("filled", o.status(),
                "bitcoin's kind is 'crypto', so the gate never looks at it · keying on the "
                        + "multiplier instead would have caught nothing today and broken any future "
                        + "multiplier-1 contract");
        assertEquals(1, VENUE.placements);
    }

    // ------------------------------------------------------------------ helpers

    private static void listOption(String symbol, LocalDate expiry) throws Exception {
        try (Connection c = BrokerDb.open();
             var ps = c.prepareStatement("""
                     INSERT INTO instruments(symbol, kind, asset_code, settle_ccy, display_name,
                                             exchange, multiplier, expires_on)
                     VALUES (?, 'option', ?, 'EUR', ?, 'OPR', ?, ?)
                     ON CONFLICT (symbol) DO UPDATE
                        SET multiplier = EXCLUDED.multiplier, expires_on = EXCLUDED.expires_on""")) {
            ps.setString(1, symbol);
            ps.setString(2, symbol);
            ps.setString(3, symbol);
            ps.setBigDecimal(4, HUNDRED);
            ps.setDate(5, java.sql.Date.valueOf(expiry));
            ps.executeUpdate();
        }
    }

    private static int fillCount() throws Exception {
        try (Connection c = BrokerDb.open();
             var ps = c.prepareStatement("SELECT count(*) FROM fills");
             var rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
