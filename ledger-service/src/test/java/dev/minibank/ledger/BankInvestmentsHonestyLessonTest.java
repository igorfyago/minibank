package dev.minibank.ledger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE BANK'S INVESTMENTS TILE HAS TO BE AS HONEST AS THE BROKER'S SCREEN.
 *
 * They are two views of one customer's holdings and they were telling
 * different stories about the same instant · which is worse than either story
 * being wrong on its own, because a reader who checks both cannot tell which
 * to believe.
 *
 * TWO DEFECTS, ONE BLOCK.
 *
 * The first was a REASON. The tile folded expiry into `unpriced` and shipped
 * no expired count, so a customer holding one dead contract was told their net
 * worth was unavailable "because a holding has no live price right now" · a
 * claim that the feed is down, made on a day the feed was working perfectly
 * and had deliberately not been asked. The truth existed only in a row-level
 * priceSource the tile's own headline does not read.
 *
 * The second was a NUMBER, and it was the formula Broker.flowsSince exists to
 * reject. `invested - prevValue` is qty_now * (price - prevClose), so a
 * position opened this morning was credited with a whole day's move it was
 * never exposed to. Buy 10 AAPL at 20.00 on a day whose prior close was 10.00
 * and this tile said +100.00 and +100.00% while the broker's portfolio screen
 * said +0.00 for the same holding at the same moment. The bigger number was
 * the invented one, and it looked entirely reasonable.
 *
 *   lesson 1  an EXPIRED holding is counted as expired, not as unpriced, and
 *             the tile withholds the total for a stated reason
 *   lesson 2  the day move is corrected for what traded inside the window ·
 *             a position opened today contributes its real gain, not a day's
 *   lesson 3  ... and gets NO percentage, because it had no prior-close value
 *             to be a percentage of
 *   lesson 4  the correction's inputs are real: flowToday recovers today's
 *             units and cash from this service's own entries, without reading
 *             the broker's database
 *
 * Requires: docker compose up -d   (shards :5434/:5435, control :5433)
 */
class BankInvestmentsHonestyLessonTest {

    static final long IGOR = 10;
    static final BigDecimal HUNDRED = new BigDecimal("100");

    /** listed by this test and by nothing else · expiry is set per lesson */
    static final String CALL = "AAPL240119C00250000";

    static HttpServer bank;
    static int port;
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    static void boot() throws Exception {
        Shards.boot(
                System.getenv().getOrDefault("MINIBANK_SHARD0_URL", "jdbc:postgresql://localhost:5434/minibank"),
                System.getenv().getOrDefault("MINIBANK_SHARD1_URL", "jdbc:postgresql://localhost:5435/minibank"),
                "minibank", "minibank", 5);
        for (Shard s : Shards.all()) s.createSchema();
        Shards.nameRegions("eu", "uk");
        bank = HttpApi.start(0);
        port = bank.getAddress().getPort();
    }

    @AfterAll
    static void down() {
        if (bank != null) bank.stop(0);
    }

    @BeforeEach
    void freshWorld() throws Exception {
        Fixtures.resetShards();
        Shards.forCustomer(IGOR).createCustomer(IGOR, "igor");
        Products.ensureFor(IGOR);
        Shards.forCustomer(IGOR).transferLocal(UUID.randomUUID(), Shard.WORLD, IGOR, new BigDecimal("10000.00"));
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("lesson 1: an EXPIRED holding is counted as expired · not as a price outage")
    void lesson1_expiredIsItsOwnReason() throws Exception {
        // yesterday, so it is expired whenever this suite runs
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        AssetRegistry.register(CALL, CALL, "aapl jan 2024 250 call", HUNDRED, "option", yesterday);
        giveHolding(CALL, "2");

        // the BANK's /api/portfolio · the tile's own endpoint, not the
        // broker's route of the same name on the other port
        String body = get("/api/portfolio?customer=" + IGOR);

        // NOTHING IN THIS ASSERTION NEEDS A NETWORK. An expired contract is
        // never handed to PriceFeed at all · that refusal is the point · so
        // this lesson is deterministic in a way a priced holding could not be.
        assertEquals(1, intField(body, "expired"),
                "THE POINT: the count exists and it is the RIGHT count. The broker's aggregate has "
                + "shipped `expired` all along and the bank's block shipped none, so the two "
                + "screens disagreed about why the same total was missing and neither was right.");
        assertEquals(0, intField(body, "unpriced"),
                "and it is NOT laundered through `unpriced`, which claims the feed did not answer · "
                + "the feed was never asked, and blaming it sends the reader to check a system "
                + "that is fine");
        assertTrue(body.contains("\"value\":null"),
                "the total is still withheld · an expired contract is held and unvaluable, so a "
                + "sum of everything else is a smaller, wrong number wearing a bigger label");
        assertTrue(body.contains("\"priceSource\":\"expired\""),
                "and the row says so too, as it always did");
    }

    @Test
    @DisplayName("lesson 2: the day move is corrected for what traded TODAY · not qty_now * (price - prevClose)")
    void lesson2_dayMoveIsCorrectedForIntradayFlow() {
        // The reviewer's case, exactly: 10 shares bought today at 20.00 on a
        // day whose prior close was 10.00. The customer paid 200.00, and the
        // position is worth 200.00. They are level.
        HttpApi.DayFlow boughtToday = new HttpApi.DayFlow(
                new BigDecimal("10"), new BigDecimal("-200.00"));   // cash out is negative

        BigDecimal move = HttpApi.dayMove(new BigDecimal("10"), new BigDecimal("20.00"),
                new BigDecimal("10.00"), BigDecimal.ONE, boughtToday);

        assertEquals(0, BigDecimal.ZERO.compareTo(move),
                "THE POINT: qty_now * (price - prevClose) says +100.00 · a full day's move on a "
                + "position that did not exist this morning. Broker.flowsSince was written to "
                + "reject exactly this shape and Portfolio.build applies it; this block never "
                + "called it, so the bank and the broker reported +100.00 and +0.00 for one "
                + "holding at one instant.");

        // and the ordinary case · held all day, nothing traded · is unchanged
        BigDecimal held = HttpApi.dayMove(new BigDecimal("10"), new BigDecimal("20.00"),
                new BigDecimal("10.00"), BigDecimal.ONE, HttpApi.DayFlow.NONE);
        assertEquals(0, new BigDecimal("100.00").compareTo(held),
                "a position actually held through the move earns the move · the correction is a "
                + "correction, not a suppression");

        // the contract size rides on the price terms and never on the cash
        BigDecimal contract = HttpApi.dayMove(new BigDecimal("2"), new BigDecimal("5.00"),
                new BigDecimal("4.50"), HUNDRED, HttpApi.DayFlow.NONE);
        assertEquals(0, new BigDecimal("100.00").compareTo(contract),
                "2 contracts * 0.50 * 100 · the ledger holds contracts, not the shares they control");
    }

    @Test
    @DisplayName("lesson 3: a position opened today gets no PERCENTAGE · it had no prior-close value")
    void lesson3_noPercentageWithoutAPriorCloseValue() {
        HttpApi.DayFlow boughtToday = new HttpApi.DayFlow(
                new BigDecimal("10"), new BigDecimal("-200.00"));

        assertEquals(0, BigDecimal.ZERO.compareTo(
                        HttpApi.dayBaseOf(new BigDecimal("10"), new BigDecimal("10.00"),
                                BigDecimal.ONE, boughtToday)),
                "THE POINT: the denominator has to be what the position was worth at the PRIOR "
                + "CLOSE, and this one was worth nothing because it did not exist. The old code "
                + "divided by prevValue = qty_now * prevClose = 100.00 · money the customer never "
                + "had in this holding · and reported +100.00%.");

        // a position half of which was held through the close keeps its base
        HttpApi.DayFlow toppedUp = new HttpApi.DayFlow(
                new BigDecimal("4"), new BigDecimal("-80.00"));
        assertEquals(0, new BigDecimal("60.00").compareTo(
                        HttpApi.dayBaseOf(new BigDecimal("10"), new BigDecimal("10.00"),
                                BigDecimal.ONE, toppedUp)),
                "6 of the 10 were held at the prior close, so the base is 6 * 10.00 · leaving all "
                + "ten in would overstate the base and understate the day");
    }

    @Test
    @DisplayName("lesson 4: flowToday recovers today's trade from THIS service's own entries")
    void lesson4_theCorrectionHasRealInputs() throws Exception {
        AssetRegistry.register("MSFT", "MSFT", "microsoft", BigDecimal.ONE, "equity", null);

        // a settled buy · 3 units for 300.00, exactly as a fill would land
        Products.settleFill(UUID.randomUUID(), IGOR, "MSFT", true,
                new BigDecimal("3"), new BigDecimal("300.00"));

        Shard home = Shards.forCustomer(IGOR);
        try (Connection c = home.open()) {
            long assetAcct = AssetRegistry.ensureHolding(c, "MSFT", IGOR);
            HttpApi.DayFlow flow = HttpApi.flowToday(c, assetAcct, IGOR, todayStart());

            assertEquals(0, new BigDecimal("3").compareTo(flow.units()),
                    "THE POINT: the broker has flowsSince over its fills and this service cannot "
                    + "read that database · but the same trade wrote a units leg and a euro leg "
                    + "in ONE transaction here, so the flow is recoverable without crossing the "
                    + "boundary. Without it the correction has no inputs and the formula is "
                    + "back to qty_now.");
            assertEquals(0, new BigDecimal("-300.00").compareTo(flow.cash()),
                    "signed the way the ledger signs it · a buy takes euros out");

            // and the euro leg is scoped by TRANSACTION, not by time: the
            // customer's main account also carries transfers, card spend and
            // interest, and none of those bought an instrument
            Shards.forCustomer(IGOR).transferLocal(UUID.randomUUID(), IGOR, Shard.WORLD,
                    new BigDecimal("50.00"));
            HttpApi.DayFlow after = HttpApi.flowToday(c, assetAcct, IGOR, todayStart());
            assertEquals(0, new BigDecimal("-300.00").compareTo(after.cash()),
                    "an unrelated 50.00 leaving the account is not part of what this holding cost");
            assertNotEquals(0, after.units().signum(), "and the units leg is untouched by it");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static java.time.Instant todayStart() {
        return LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** Put units in the customer's holding account without going near a venue. */
    private static void giveHolding(String symbol, String units) throws Exception {
        Products.settleFill(UUID.randomUUID(), IGOR, symbol, true,
                new BigDecimal(units), new BigDecimal("900.00"));
    }

    private static String get(String path) throws Exception {
        HttpResponse<String> r = HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return r.body();
    }

    /** A named integer out of the investments block · the scanner style this bank uses. */
    private static int intField(String body, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + name + "\"\\s*:\\s*(-?\\d+)").matcher(body);
        if (!m.find()) throw new AssertionError("no \"" + name + "\" in " + body);
        return Integer.parseInt(m.group(1));
    }
}
