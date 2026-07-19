package dev.minibank.broker;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE BROKER · ORDERS, FILLS, AND WHAT A POSITION COST YOU.
 *
 * The bank already proves it never loses money. This service proves
 * something the ledger deliberately refuses to model: how you came to hold
 * what you hold, and what it cost. That is the difference between a balance
 * and a position.
 *
 *   lesson 1  the client's order id is the gate · a retry is not a second order
 *   lesson 2  a buy sets the average cost, and the fee is part of what it cost
 *   lesson 3  averaging down is arithmetic, not opinion
 *   lesson 4  a sell REALISES the difference · and leaves the average alone
 *   lesson 5  you cannot sell what you do not have
 *   lesson 6  the position is a projection · it must rebuild from the fills
 *   lesson 7  the fill and its event share one commit (the outbox again)
 *   lesson 8  a redelivered fill moves the position once
 *   lesson 9  fills are append-only, and the DATABASE says so
 *   lesson 10 the venue is a port · an unconfigured one refuses, it does not fake
 *
 * Requires: docker compose up -d   (control-plane Postgres on :5433)
 */
class BrokerLessonTest {

    static final long IGOR = 10;
    /** a venue with no market of its own · every price is stated by the test */
    static final StubVenue VENUE = new StubVenue();
    static Broker broker;

    @BeforeAll
    static void boot() throws Exception {
        BrokerDb.createOwnDatabase();
        Catalog.seed();
        broker = new Broker(VENUE);
    }

    @BeforeEach
    void freshBook() throws Exception {
        try (Connection c = BrokerDb.open(); var st = c.createStatement()) {
            st.execute("SET lock_timeout = '4s'");
            // fills is append-only by trigger, so DELETE is refused · TRUNCATE
            // is a different privilege and is how you reset a fixture
            st.execute("TRUNCATE fills, orders, positions, watchlist, account_link, outbox");
        }
        Catalog.seed();
        VENUE.reset();
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 1: the client's order id is the gate · a retried POST is not a second order")
    void lesson1_idempotentOrders() throws Exception {
        VENUE.fillAt("50000.00");
        Broker.Order first = buy("order-abc", "BTC", "100.00");
        Broker.Order retry = buy("order-abc", "BTC", "100.00");

        assertEquals(first.id(), retry.id(), "the same client order id is the same order");
        assertEquals(1, VENUE.placements, "and the venue heard about it once");
        assertEquals(1, fillCount(), "one fill, not two");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 2: a buy sets the average cost · and the fee is part of what it cost you")
    void lesson2_costBasisIncludesFees() throws Exception {
        VENUE.fillAt("50000.00").withFee("5.00");
        buy("o1", "BTC", "1000.00");                       // 0.02 BTC at 50k, 5 fee

        Broker.Position p = position("BTC");
        assertEquals(0, dec("0.02").compareTo(p.qty()));
        assertEquals(0, dec("1005.00").compareTo(p.costBasis()),
                "1000 of bitcoin plus 5 of fee · the fee is not free");
        assertEquals(0, dec("50250.00").compareTo(p.averageCost()),
                "so the average cost is above the price you paid");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 3: averaging down · the second buy moves the average, exactly as far as the arithmetic says")
    void lesson3_averagingDown() throws Exception {
        VENUE.fillAt("50000.00");
        buy("o1", "BTC", "1000.00");                       // 0.02 at 50k
        VENUE.fillAt("40000.00");
        buy("o2", "BTC", "1000.00");                       // 0.025 at 40k

        Broker.Position p = position("BTC");
        assertEquals(0, dec("0.045").compareTo(p.qty()));
        assertEquals(0, dec("2000.00").compareTo(p.costBasis()));
        // 2000 / 0.045 = 44444.44444444
        assertEquals(0, dec("44444.44444444").compareTo(p.averageCost()),
                "between the two prices, weighted by how much you bought at each");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 4: a sell realises the gain · and leaves the average cost of what remains untouched")
    void lesson4_sellRealises() throws Exception {
        VENUE.fillAt("50000.00");
        buy("o1", "BTC", "1000.00");                       // 0.02 at 50k, avg 50k
        VENUE.fillAt("60000.00");
        sell("o2", "BTC", "0.01");                         // half, 10k higher

        Broker.Position p = position("BTC");
        assertEquals(0, dec("0.01").compareTo(p.qty()), "half is left");
        assertEquals(0, dec("100.00").compareTo(p.realizedPnl()),
                "0.01 sold 10000 above its average cost = 100 realised");
        assertEquals(0, dec("500.00").compareTo(p.costBasis()), "and half the basis left with it");
        assertEquals(0, dec("50000.00").compareTo(p.averageCost()),
                "THE POINT: selling does not change what the rest cost you");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 5: you cannot sell what you do not have · the position is a floor, not a suggestion")
    void lesson5_noNakedShorts() throws Exception {
        VENUE.fillAt("50000.00");
        buy("o1", "BTC", "1000.00");                       // 0.02

        assertThrows(IllegalArgumentException.class, () -> sell("o2", "BTC", "0.05"),
                "selling more than you hold is a short position, and this service does not offer one");
        assertEquals(0, dec("0.02").compareTo(position("BTC").qty()), "and nothing moved");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 6: the position is a PROJECTION · it must rebuild from the fills that made it")
    void lesson6_positionRebuildsFromFills() throws Exception {
        VENUE.fillAt("50000.00");
        buy("o1", "BTC", "1000.00");
        VENUE.fillAt("52000.00");
        buy("o2", "BTC", "520.00");
        VENUE.fillAt("55000.00");
        sell("o3", "BTC", "0.005");

        assertEquals(List.of(), Broker.audit(), "the stored position agrees with the fills");

        // corrupt the copy, exactly as cache drift would
        try (Connection c = BrokerDb.open(); var st = c.createStatement()) {
            st.execute("UPDATE positions SET qty = qty + 1 WHERE symbol = 'BTC'");
        }
        assertNotEquals(List.of(), Broker.audit(),
                "and when it does not, the audit says so instead of the customer finding out");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 7: the fill and the event announcing it share ONE commit · the outbox, again")
    void lesson7_fillAndEventAreOneCommit() throws Exception {
        VENUE.fillAt("50000.00");
        buy("o1", "BTC", "1000.00");

        // Read the row whatever its published_at. pollUnpublished is the
        // RELAY's read and is right for the relay; for a test it asserts "no
        // relay has shipped this yet", which is a race, not a property. The
        // claim here is that the event was written inside the fill's own
        // commit · not that nobody has forwarded it since.
        List<BrokerDb.Event> events = allBrokerOutbox();
        assertEquals(1, events.size(), "one fill, one event");
        BrokerDb.Event e = events.get(0);
        assertEquals(Broker.TOPIC_ORDERS, e.topic());
        assertTrue(e.key().startsWith("filled:"));
        assertTrue(e.payload().contains("\"type\":\"order.filled\""));
        assertTrue(e.payload().contains("\"customer\":10"));
        assertTrue(e.payload().contains("\"cash\":\"1000.00\""),
                "the cash leg the ledger will settle · money as a string, ids as numbers");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 8: a venue redelivers a fill · the position moves once")
    void lesson8_redeliveredFillIsIgnored() throws Exception {
        VENUE.fillAt("50000.00");
        Broker.Order o = buy("o1", "BTC", "1000.00");
        BigDecimal after = position("BTC").qty();

        // the same fill arrives again off the venue's stream, as it will
        try (Connection c = BrokerDb.open()) {
            broker.recordFill(c, o.id(), VENUE.lastFill);
        }
        assertEquals(0, after.compareTo(position("BTC").qty()), "at-least-once delivery, exactly-once effect");
        assertEquals(1, fillCount());
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 9: a fill is a fact · the database refuses to let anyone rewrite one")
    void lesson9_fillsAreAppendOnly() throws Exception {
        VENUE.fillAt("50000.00");
        buy("o1", "BTC", "1000.00");

        try (Connection c = BrokerDb.open(); var st = c.createStatement()) {
            assertThrows(java.sql.SQLException.class,
                    () -> st.execute("UPDATE fills SET price = 1"),
                    "correct a bad fill with another fill, never with an UPDATE");
            assertThrows(java.sql.SQLException.class, () -> st.execute("DELETE FROM fills"));
        }
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 10: the venue is a PORT · an unconfigured one refuses loudly instead of inventing a fill")
    void lesson10_unconfiguredVenueRefuses() throws Exception {
        Broker live = new Broker(new IbkrVenue());
        Broker.Order o = live.place("o-live", IGOR, "BTC", "buy",
                null, dec("100.00"), "market", null);

        assertEquals("rejected", o.status(), "no venue, no order");
        assertTrue(o.rejectReason().contains("ibkr"), "and it names what refused: " + o.rejectReason());
        assertEquals(0, position("BTC").qty().signum(), "nothing was invented");
        assertEquals(0, fillCount(), "in particular, no fill was fabricated");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("identity: a desk session claims a bank customer, and the watchlist stops living in a browser")
    void identityAndWatchlist() throws Exception {
        assertNull(Accounts.customerFor("sess-1"), "an unclaimed session is nobody");
        Accounts.link("sess-1", IGOR);
        assertEquals(IGOR, Accounts.customerFor("sess-1"));

        Accounts.watch(IGOR, "btc");
        Accounts.watch(IGOR, "BTC");                       // same symbol, normalised
        Accounts.watch(IGOR, "AAPL");
        assertEquals(List.of("BTC", "AAPL"), Accounts.watchlist(IGOR), "kept in the order you added them");

        Accounts.unwatch(IGOR, "BTC");
        assertEquals(List.of("AAPL"), Accounts.watchlist(IGOR));
    }

    // ------------------------------------------------------------------
    /** every broker outbox row, published or not · see lesson 7 */
    static List<BrokerDb.Event> allBrokerOutbox() throws Exception {
        List<BrokerDb.Event> out = new java.util.ArrayList<>();
        try (Connection c = BrokerDb.open();
             var ps = c.prepareStatement("SELECT id, topic, key, payload FROM outbox ORDER BY id");
             var rs = ps.executeQuery()) {
            while (rs.next())
                out.add(new BrokerDb.Event(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
        }
        return out;
    }

    private static BigDecimal dec(String v) { return new BigDecimal(v); }

    private Broker.Order buy(String clientId, String symbol, String notional) throws Exception {
        return broker.place(clientId, IGOR, symbol, "buy", null, dec(notional), "market", null);
    }

    private Broker.Order sell(String clientId, String symbol, String qty) throws Exception {
        return broker.place(clientId, IGOR, symbol, "sell", dec(qty), null, "market", null);
    }

    private static Broker.Position position(String symbol) throws Exception {
        try (Connection c = BrokerDb.open()) {
            return Broker.positionOn(c, IGOR, symbol);
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

    /**
     * A venue that fills at whatever the test says, so the lessons are about
     * cost basis rather than about what bitcoin happened to cost today.
     * Real price feeds belong in SimulatedVenue, not in an assertion.
     */
    static final class StubVenue implements BrokerPort {
        BigDecimal price = new BigDecimal("100.00");
        BigDecimal fee = BigDecimal.ZERO;
        int placements = 0;
        Fill lastFill;

        StubVenue fillAt(String p) { this.price = new BigDecimal(p); return this; }
        StubVenue withFee(String f) { this.fee = new BigDecimal(f); return this; }
        void reset() { placements = 0; fee = BigDecimal.ZERO; lastFill = null; }

        @Override public String name() { return "stub"; }
        @Override public BigDecimal quote(String symbol) { return price; }

        @Override public Ack place(OrderRequest r) {
            placements++;
            BigDecimal qty = r.qty() != null
                    ? r.qty()
                    : r.notional().divide(price, 8, java.math.RoundingMode.DOWN);
            lastFill = new Fill(qty, price, fee, "stub-" + UUID.randomUUID());
            return Ack.filled("stubref", lastFill);
        }
    }
}
