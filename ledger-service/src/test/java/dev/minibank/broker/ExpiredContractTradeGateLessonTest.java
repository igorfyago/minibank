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
 * A DEAD CONTRACT IS NOT A PRODUCT · the order path has to know about expiry.
 *
 * The valuation side of this service has understood expiry for a while.
 * Portfolio.build refuses to value an expired contract, BrokerApi.positions
 * refuses, and the bank's investments tile refuses. All three are READS, and
 * every one of them ran after the money had already moved.
 *
 * The order path knew nothing. BrokerApi.orders gated on Catalog.exists, and
 * the catalog row is not deleted at expiry · it cannot be, because a customer
 * may still hold the position and every screen needs the name, the contract
 * size and the date to say so. So `exists` stayed true and a dead contract
 * stayed buyable. SimulatedVenue then priced it from PriceFeed, Yahoo answered
 * 404 the way it does for any contract that has stopped existing, and
 * PriceFeed's upstream-down branch handed back the last premium it ever saw
 * relabelled 'cached' with NO age bound. The venue filled against that number,
 * the fill settled, and real euros left the customer's account for an
 * instrument this same codebase then refuses to price · leaving a position
 * that withholds their entire portfolio total forever, because Acc.whole()
 * counts expired rows and nothing sweeps them.
 *
 *   lesson 1  buying an expired contract is REFUSED, and no fill is written
 *   lesson 2  selling one is refused too · there is no bid on a dead contract,
 *             and a fill would pay out cash against a price nobody quoted
 *   lesson 3  the boundary is the same one the valuation side uses · a
 *             contract trades ON its expiry day and not after
 *   lesson 4  the VENUE refuses on its own account, before it asks for a price
 *   lesson 5  an instrument that does not expire is untouched
 *
 * THE STUB VENUE FILLS EVERYTHING IT IS ASKED TO FILL. That is the point: it
 * has no market and no opinion, so if the gate in Broker.place is removed
 * these lessons fill and fail. The gate is what is under test, not the venue's
 * good judgement.
 *
 * Requires: docker compose up -d   (control-plane Postgres on :5433)
 */
class ExpiredContractTradeGateLessonTest {

    static final long IGOR = 10;
    static final BigDecimal HUNDRED = new BigDecimal("100");

    /** An OCC-style contract, listed by this test and by nothing else. */
    static final String DEAD = "AAPL240119C00250000";
    static final String ALIVE = "AAPL260821C00250000";

    /** a venue that fills whatever it is handed · see the class comment */
    static final AlwaysFills VENUE = new AlwaysFills();
    static Broker broker;

    @BeforeAll
    static void boot() throws Exception {
        BrokerDb.createOwnDatabase();
        broker = new Broker(VENUE);
    }

    @BeforeEach
    void freshBook() throws Exception {
        try (Connection c = BrokerDb.open(); var st = c.createStatement()) {
            st.execute("SET lock_timeout = '4s'");
            st.execute("TRUNCATE fills, orders, positions, watchlist, account_link, outbox");
        }
        Catalog.seed();
        // RELATIVE TO TODAY, not a fixed date, so "has it expired" cannot
        // start answering differently the year this test is still running.
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        listContract(DEAD, today.minusDays(1));
        listContract(ALIVE, today.plusYears(1));
        VENUE.reset();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("lesson 1: buying an EXPIRED contract is refused · and no fill is written")
    void lesson1_expiredContractCannotBeBought() throws Exception {
        Broker.Order o = broker.place("buy-dead", IGOR, DEAD, "buy",
                new BigDecimal("1"), null, "market", null);

        assertEquals("rejected", o.status(),
                "THE POINT: money must not move for an instrument this codebase refuses to price. "
                + "The catalog row survives expiry on purpose, so Catalog.exists cannot be the gate.");
        assertNotNull(o.rejectReason(), "and a refusal says why, or the customer is left guessing");
        assertTrue(o.rejectReason().contains("expired"),
                "it names the actual reason · 'no price' would blame a feed that was never asked");

        assertEquals(0, VENUE.placements,
                "the venue is not even consulted · asking it is what produced the stale premium, "
                + "because PriceFeed relabels the last mark it saw as 'cached' with no age bound");
        assertEquals(0, fillCount(),
                "no fill, so nothing to settle · a fill here becomes euros out of a real account");
        assertEquals(0, Broker.positions(IGOR).size(),
                "and no position · one of these is permanently unvaluable and withholds "
                + "the customer's entire portfolio total for as long as they hold it");
    }

    @Test
    @DisplayName("lesson 2: selling an expired contract is refused too · there is no bid on a dead contract")
    void lesson2_expiredContractCannotBeSold() throws Exception {
        Broker.Order o = broker.place("sell-dead", IGOR, DEAD, "sell",
                new BigDecimal("1"), null, "market", null);

        assertEquals("rejected", o.status(),
                "THE POINT: this is not an oversight in the buy gate. A sell would fill at the "
                + "same stale premium and pay CASH OUT against a price nobody quoted, which is "
                + "the buy bug with the sign flipped. Unwinding an expired position is an expiry "
                + "settlement · a deliberate act with a settlement price · and not an order.");
        assertTrue(o.rejectReason().contains("expired"), "and it says so");
        assertEquals(0, fillCount(), "no fill on the way out either");
    }

    @Test
    @DisplayName("lesson 3: a contract trades ON its expiry day · the gate uses the valuation boundary")
    void lesson3_theBoundaryMatchesTheValuationSide() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String expiringToday = "AAPL999999C00250000";
        listContract(expiringToday, today);

        Broker.Order o = broker.place("buy-today", IGOR, expiringToday, "buy",
                new BigDecimal("1"), null, "market", null);

        assertEquals("filled", o.status(),
                "Catalog.Instrument.expiredAsOf is inclusive of the date itself, and the order "
                + "gate uses that same predicate rather than a second copy of the rule. Retiring "
                + "a contract a day early refuses a trade that was still perfectly good, and two "
                + "copies of a boundary is how the screen and the order path start disagreeing "
                + "about whether something is tradable.");
    }

    @Test
    @DisplayName("lesson 4: the VENUE refuses on its own account, before it asks for a price")
    void lesson4_theVenueRefusesWithoutBeingTold() {
        SimulatedVenue venue = new SimulatedVenue();
        BrokerPort.Ack ack = venue.place(new BrokerPort.OrderRequest(
                "direct", IGOR, DEAD, "buy", new BigDecimal("1"), null, "market", null));

        assertTrue(!ack.accepted(), "a venue that cannot obtain a CURRENT price must refuse");
        assertTrue(ack.rejectReason().contains("expired"),
                "THE POINT: the check sits ABOVE the quote in place(). quote() is PriceFeed.get, "
                + "and asking it about a dead contract is what returns the final premium wearing "
                + "a live mark's clothes · so the order of those two lines is the fix, not a "
                + "second opinion about it. Broker.place gates this too, because that gate has "
                + "to hold for IbkrVenue as well; this one holds when the caller forgot.");
    }

    @Test
    @DisplayName("lesson 5: an instrument that does not expire is untouched by any of this")
    void lesson5_ordinaryInstrumentsAreUnaffected() throws Exception {
        Broker.Order stock = broker.place("buy-aapl", IGOR, "AAPL", "buy",
                new BigDecimal("1"), null, "market", null);
        assertEquals("filled", stock.status(),
                "expires_on is null for a share, so expiredAsOf is false and there is no branch "
                + "asking whether this is an option · the ordinary case stays ordinary");

        Broker.Order live = broker.place("buy-live", IGOR, ALIVE, "buy",
                new BigDecimal("1"), null, "market", null);
        assertEquals("filled", live.status(), "and a contract with a future expiry is tradable");
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A row in the broker's catalog, and nothing else.
     *
     * Catalog.list() would also write the ledger's asset registry, which needs
     * shards booted. What is under test here is the BROKER's order gate, so
     * the fixture stops at the broker's own table rather than dragging two
     * databases into a question neither of them is being asked.
     */
    private static void listContract(String symbol, LocalDate expiry) throws Exception {
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

    /** No market, no opinion · it fills whatever it is handed. */
    static final class AlwaysFills implements BrokerPort {
        int placements;

        void reset() { placements = 0; }

        @Override public String name() { return "always-fills"; }

        @Override public BigDecimal quote(String symbol) { return new BigDecimal("5.00"); }

        @Override public Ack place(OrderRequest r) {
            placements++;
            BigDecimal qty = r.qty() != null ? r.qty() : new BigDecimal("1");
            return Ack.filled("stub-" + placements,
                    new Fill(qty, new BigDecimal("5.00"), BigDecimal.ZERO, "stubfill-" + placements));
        }
    }
}
