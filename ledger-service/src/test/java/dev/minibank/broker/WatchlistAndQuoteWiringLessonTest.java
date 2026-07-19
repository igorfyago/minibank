package dev.minibank.broker;

import dev.minibank.ledger.PriceFeed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE WIRING BETWEEN A CAREFUL FUNCTION AND THE CALLER THAT STARVES IT.
 *
 * Both defects here are the same shape: a piece of logic that is correct,
 * tested, and never reached with the inputs it was written for.
 *
 * ONE · WHICH POSITIONS GET A QUOTE. Portfolio.build has a whole branch for a
 * position closed to flat today, because such a position still moved money
 * today and its day P&L belongs in the headline. BrokerApi.portfolio asked for
 * a mark only where qty was non-zero, so that branch always received
 * Quote.none(), always fell through to withoutPrevClose, and Acc.day() nulls
 * the aggregate the moment that counter moves. One position sold to flat
 * therefore withheld the WHOLE book's day change for the rest of the UTC day,
 * while every remaining holding displayed its own day change perfectly well ·
 * and the page attributed it to a holding that was never drawn. The
 * day-honesty test passed the entire time, because it hands Portfolio.build a
 * quotes map directly and never touches this predicate.
 *
 * TWO · WHAT THE FEED COSTS AND WHAT IT ADMITS. The watchlist priced every row
 * with a sequential upstream call and threw away Px.source(). Sequential is
 * unaffordable at rail size · a hundred-odd tickers against a two-second
 * client budget · so the shared watchlist never bootstrapped for a real user
 * at all. And discarding the source made a 'cached' mark of unbounded age
 * render identically to a live one, on the same page whose holdings rows badge
 * exactly that condition as "last known price".
 *
 *   lesson 1  a position closed to flat TODAY is quoted · that is what makes
 *             the closed-position branch reachable
 *   lesson 2  ... and one that did not trade today is not, because it cannot
 *             spoil a day it took no part in
 *   lesson 3  a batch of marks is fetched CONCURRENTLY, not one round trip
 *             per row
 *   lesson 4  a symbol the feed could not price is not re-asked immediately ·
 *             the backoff caches the ATTEMPT and never the answer
 *
 * Requires: docker compose up -d   (control-plane Postgres on :5433)
 */
class WatchlistAndQuoteWiringLessonTest {

    static final long IGOR = 10;

    // ------------------------------------------------------------------
    // lessons 1-2 are PURE · the predicate is the defect, so it is asserted
    // directly rather than through a feed whose answers a test cannot choose
    // ------------------------------------------------------------------

    @Test
    @DisplayName("lesson 1: a position closed to flat TODAY is still quoted · the day is not over for it")
    void lesson1_closedTodayIsQuoted() {
        List<Broker.Position> positions = List.of(
                flat("AAPL"),                       // sold out today
                open("BTC", "0.5"));                // still held

        Map<String, Broker.DayFlow> flows = Map.of(
                "AAPL", new Broker.DayFlow(new BigDecimal("-10"), new BigDecimal("-2000")));

        Set<String> want = BrokerApi.symbolsNeedingQuotes(positions, flows);

        assertTrue(want.contains("AAPL"),
                "THE POINT: without a mark, Portfolio.build's closed-position branch gets "
                + "Quote.none(), fails q.observed(), and increments withoutPrevClose · which nulls "
                + "the WHOLE book's day change, for the rest of the day, and the page then blames "
                + "a holding it did not draw. Buy AAPL, sell all of it, buy BTC: BTC's row shows a "
                + "day change and the headline says the day is not available.");
        assertTrue(want.contains("BTC"), "and a position still held is quoted as it always was");
        assertEquals(2, want.size(), "exactly those two");
    }

    @Test
    @DisplayName("lesson 2: a flat position that did NOT trade today is not quoted · it took no part in the day")
    void lesson2_flatAndUntradedIsNotQuoted() {
        List<Broker.Position> positions = List.of(flat("AAPL"), open("BTC", "0.5"));

        // AAPL was closed on some earlier day · Broker.positions still returns
        // the row, because its realised P&L is a fact about money that moved
        Set<String> want = BrokerApi.symbolsNeedingQuotes(positions, Map.of());

        assertFalse(want.contains("AAPL"),
                "quoting everything would be the easy over-correction and it costs an upstream "
                + "call per historical position forever · a row that did not trade today "
                + "contributes nothing to the day and cannot spoil it");
        assertEquals(Set.of("BTC"), want, "only what is held or traded");
    }

    // ------------------------------------------------------------------
    // lessons 3-4 touch the real feed, deliberately · what is under test is
    // how this class BEHAVES when upstream will not answer, and a fake feed
    // that answers instantly is exactly what hid the defect
    // ------------------------------------------------------------------

    @Test
    @DisplayName("lesson 3: a batch of marks is fetched CONCURRENTLY · sequential does not fit any budget")
    void lesson3_marksAreFetchedInParallel() {
        // THE COST OF ONE LOOKUP IS CHOSEN HERE, and that is the only way this
        // lesson means anything. Timing the real feed does not discriminate:
        // on a machine with no network every upstream call fails in
        // microseconds, so forty sequential calls finish as fast as forty
        // parallel ones and the assertion passes either way. In production
        // each call is a live Yahoo or CoinGecko request with a 3s connect and
        // 4s read timeout, which is what this stands in for.
        List<String> keys = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) keys.add("k" + i);

        long t0 = System.nanoTime();
        Map<String, String> out = PriceFeed.fanOut(keys, k -> {
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return k.toUpperCase(java.util.Locale.ROOT);
        });
        long elapsed = (System.nanoTime() - t0) / 1_000_000;

        assertEquals(40, out.size(),
                "every key gets an answer · an unpriced symbol is an admission, not an omission");
        assertEquals("K7", out.get("k7"), "and the answers are not shuffled between keys");

        assertTrue(elapsed < 1500,
                "THE POINT: this is a fan-out, not a loop. 40 lookups of 100ms each took "
                + elapsed + "ms; sequentially they are 4000ms and no budget accommodates that. "
                + "The desk's real rail is 109 symbols against a 2.0s client timeout, and it was "
                + "measured at 5326ms in an environment where every upstream call failed in about "
                + "40ms · the floor, not the bad case. The bootstrap timed out on every load, so "
                + "the migration behind it never ran once, and every existing test passed because "
                + "each of them replaces the HTTP client with an instant fake.");
    }

    @Test
    @DisplayName("lesson 3b: the batch answers for every symbol, including the ones it cannot price")
    void lesson3b_everySymbolGetsAnAnswer() {
        PriceFeed.resetLocalCaches();
        List<String> batch = List.of("zzqx1", "zzqx2", "zzqx3", "zzqx1");

        Map<String, PriceFeed.Px> marks = PriceFeed.getAll(batch);

        assertEquals(3, marks.size(),
                "duplicates collapse before the fan-out · a rail that names a symbol twice "
                + "costs one upstream call, not two");
        assertTrue(marks.values().stream().noneMatch(PriceFeed.Px::priced),
                "no exchange lists these, so none of them is priced · and an unpriced symbol "
                + "comes back as an admission rather than being dropped from the map, because a "
                + "missing key and a missing price render very differently");
    }

    @Test
    @DisplayName("lesson 4: a symbol the feed could not price is not re-asked immediately")
    void lesson4_theAttemptIsCachedAndNeverTheAnswer() {
        PriceFeed.resetLocalCaches();
        // a symbol nothing lists · unique per run, so a warm Redis entry from
        // an earlier run cannot decide the outcome
        String sym = "zzqx" + System.nanoTime();

        // COUNTED, NOT TIMED. A stopwatch cannot tell a cached miss from an
        // upstream one on a machine where the upstream fails in microseconds,
        // which is exactly the machine this suite runs on · so a timing
        // assertion here passes whether or not the backoff exists.
        long before = PriceFeed.upstreamAttempts();
        PriceFeed.Px first = PriceFeed.get(sym);
        long afterFirst = PriceFeed.upstreamAttempts();
        PriceFeed.Px second = PriceFeed.get(sym);
        long afterSecond = PriceFeed.upstreamAttempts();

        assertFalse(first.priced(), "the feed has nothing for this");
        assertFalse(second.priced(), "and the second answer is the same admission");
        assertEquals(1, afterFirst - before, "the first call does go upstream · it has to");
        assertEquals(0, afterSecond - afterFirst,
                "THE POINT: the second does not. A failed price was never cached at all, so every "
                + "call for an unpriceable symbol was a fresh upstream request with a 3s connect "
                + "and 4s read timeout, and nothing anywhere absorbed it. The desk's rail is full "
                + "of exactly these · indices the venue cannot fill · so the shared watchlist "
                + "paid that cost on every poll and could not answer inside a 2.0s budget.");

        assertEquals("unavailable", second.source(),
                "AND THE SHAPE OF THE FIX MATTERS AS MUCH AS THE FIX: the backoff holds a "
                + "TIMESTAMP and never a number, so it can never become the `hit` that the "
                + "upstream-down branch relabels 'cached'. That laundering route is exactly what "
                + "unavailable() was written to close · a 'cached' here would be an invented "
                + "price wearing an old real one's clothes.");
    }

    @Test
    @DisplayName("lesson 5: the watchlist SHIPS priceSource · the source has to survive the trip to the screen")
    void lesson5_theWatchlistDisclosesWhereItsMarkCameFrom() throws Exception {
        BrokerDb.createOwnDatabase();
        dev.minibank.ledger.Fixtures.resetBrokerDb();
        Catalog.seed();
        com.sun.net.httpserver.HttpServer server =
                new BrokerApi(new Broker(new BrokerLessonTest.StubVenue())).start(0);
        try {
            int port = server.getAddress().getPort();
            // an index nothing here can price · the ordinary case on this rail
            Accounts.watch(IGOR, "ZZQXINDEX");

            String body = java.net.http.HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                            "http://localhost:" + port + "/api/watchlist?customer=" + IGOR)).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString()).body();

            assertTrue(body.contains("\"priceSource\""),
                    "THE POINT: mark() returned px.price() and DROPPED px.source(), so a 'cached' "
                    + "mark of unbounded age rendered as a plain figure, identical to a live one · "
                    + "on the same page whose holdings rows badge exactly that condition as 'last "
                    + "known price' and whose 46px total is qualified for it. Two honesty "
                    + "standards for one feed on one screen, and the watchlist was the one that "
                    + "lied. The page cannot disclose what the endpoint will not tell it.\n" + body);
            assertTrue(body.contains("\"priceSource\":\"unavailable\""),
                    "and an unpriceable symbol says so rather than going quiet · " + body);
            assertTrue(body.contains("\"price\":null"),
                    "the price itself is still null and never 0.00 · that part was already right");
        } finally {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Broker.Position flat(String symbol) {
        return new Broker.Position(IGOR, symbol, BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("120.00"));
    }

    private static Broker.Position open(String symbol, String qty) {
        return new Broker.Position(IGOR, symbol, new BigDecimal(qty),
                new BigDecimal("1000.00"), BigDecimal.ZERO);
    }
}
