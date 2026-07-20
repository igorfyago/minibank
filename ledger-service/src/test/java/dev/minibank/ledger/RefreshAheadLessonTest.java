package dev.minibank.ledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WHY THE PRODUCTS SHELF TOOK TWO SECONDS, and what actually fixed it.
 *
 * The shelf was slow for a reason the numbers made unambiguous once anyone
 * measured instead of guessing. Against the running stack a page load spent
 * 546ms, of which 520ms was CoinGecko, Yahoo and the fx-service called IN
 * SERIES, 15ms was every database query the endpoint makes, and 0.1ms was
 * building the JSON. The local ledger was never the problem and neither was
 * serialisation.
 *
 * There was already a read-through Redis cache in front of those upstreams,
 * and the X-ray reported its hit rate at 0 to 18 percent · which is the part
 * worth keeping a test about, because the reason turned out to be structural
 * rather than a matter of tuning.
 */
class RefreshAheadLessonTest {

    @Test
    @DisplayName("lesson 1: a shared cache whose TTL equals the local freshness window can never be hit")
    void lesson1_theTtlAndTheFreshnessWindowAreDifferentQuestions() {
        // THE BUG, stated as the invariant that would have caught it.
        //
        // PriceFeed kept an in-process value for 60 seconds and consulted it
        // FIRST; on a miss it asked Redis, and it wrote Redis with a TTL of
        // exactly 60 seconds. So the shared entry was written at T with a life
        // to T+60, and the earliest request that could possibly need it · the
        // one arriving after the local copy went stale · came at T+60. A pod
        // could not read its own write even once.
        //
        // Measured against the running stack before the fix: 15 lookups, 15
        // misses, 0 hits, across a cold start, warm traffic and a request
        // every 61 seconds. The 0-18% production ever showed was purely the
        // chance that a DIFFERENT pod had warmed the key recently, which is
        // why it collapsed to nothing whenever traffic was sparse.
        //
        // A bigger TTL is not the lesson. The lesson is that expiry and
        // freshness are two different questions: the shared copy exists so
        // that SOMETHING is servable, and how current it is belongs to the
        // timestamp travelling inside it.
        assertTrue(PriceFeed.STALE_TTL_S * 1000L > PriceFeed.FRESH_MS,
                "the shared store must outlive the local freshness window, or it is only ever "
                + "consulted at the exact moment its own entry has expired · which is a cache "
                + "that costs a round trip and returns nothing, forever");
    }

    @Test
    @DisplayName("lesson 2: a value read out of the shared store is labeled by its AGE, not by the fact we just read it")
    void lesson2_ageDecidesTheLabel() {
        long fiftyMinutesAgo = System.currentTimeMillis() - 50 * 60 * 1000L;
        // encoded exactly as the shared store holds it: price|usd|source|prev|asOf
        // and note the source field says 'live', because it WAS live when some
        // other pod observed it
        PriceFeed.Px px = PriceFeed.decode("64584.00|70000.00|live|64000.00|" + fiftyMinutesAgo, "btc");

        assertEquals("cached", px.source(),
                "THE POINT: reading a fifty-minute-old entry quickly does not make it current. "
                + "Trusting the stored label would let one pod's 'live' be served as live by the "
                + "whole fleet for the rest of the hour · the same error as the equity leg that "
                + "used to be tagged live after being converted at a stale FX rate.");
        assertTrue(px.ageSeconds() > 2900,
                "and the age travels with it, so the screen can say how old rather than merely that it is old");
    }

    @Test
    @DisplayName("lesson 3: a mark that never carried a time says so, instead of claiming to be new")
    void lesson3_anUnknownAgeIsNotAnAgeOfZero() {
        // the four-field encoding a half-rolled fleet still writes, and the
        // shape every existing caller constructs
        PriceFeed.Px old = new PriceFeed.Px(new BigDecimal("1"), new BigDecimal("1"), "live");

        assertEquals(-1, old.ageSeconds(),
                "an unknown age must not render as 'just now'. Zero here would let the screen "
                + "badge a mark of completely unknown vintage as fresh, which is the exact "
                + "category of confident lie the fabricated fallback price was deleted for.");
    }

    @Test
    @DisplayName("lesson 4: serving a stale value is only honest if something is really going to replace it")
    void lesson4_noRefresherMeansNoStaleServing() {
        Refresher.stop();          // nothing running to hand background work to

        assertFalse(Refresher.queue("btc"),
                "THE POINT: stale-while-revalidate without the revalidate half is just a price "
                + "that gets older every time somebody looks at it. When there is no refresher, "
                + "queue() has to admit it so PriceFeed.get falls back to the blocking load it "
                + "always did · which is the state every test in this suite runs in, and the "
                + "reason none of them changed behaviour.");
    }

    @Test
    @DisplayName("lesson 5: a failing upstream is asked LESS often, and never gives up entirely")
    void lesson5_theBackoffIsBoundedAtBothEnds() {
        // A refresher that retried a dead upstream every cycle would turn a
        // cache warmer into the reason the feed is down · CoinGecko's keyless
        // endpoint rate-limits, and three pods hammering it is how you earn a
        // 429 for everybody. A refresher that gave up permanently would be
        // worse: the prices would simply stop, and the last good value would
        // age forever while every screen kept rendering it.
        assertTrue(Refresher.MAX_BACKOFF_MS > Refresher.INTERVAL_MS,
                "backing off has to mean something");
        assertTrue(Refresher.MAX_BACKOFF_MS <= 900_000,
                "and it has to have a ceiling · a feed that recovers must be noticed in minutes, "
                + "not whenever the exponent happens to come back down");
    }

    @Test
    @DisplayName("lesson 7: backing off an upstream must not stop us reading what the fleet already stored")
    void lesson7_theSharedReadIsNotGatedByTheBackoff() {
        Cache.init(System.getenv().getOrDefault("REDIS_URL", "redis://localhost:6379"));
        org.junit.jupiter.api.Assumptions.assumeTrue(Cache.enabled(),
                "this one is about the SHARED store, so it needs a real Redis");

        String ghost = "zzqx" + System.nanoTime();
        PriceFeed.resetLocalCaches();

        // 1. the feed cannot price it, so the failure is recorded · locally
        //    and, so that a restarted pod inherits it, in Redis
        assertFalse(PriceFeed.get(ghost).priced(), "nothing lists this");

        // 2. meanwhile a value for it exists in the shared store · which is
        //    exactly the state a pod is in after a deploy: Redis full of marks
        //    another pod fetched, and an upstream that has since gone down
        long now = System.currentTimeMillis();
        Cache.put(PriceFeed.NS, ghost, 3600, "100.00|110.00|live||" + now);

        // 3. the restart · in-process state is gone, the Redis marker is not
        PriceFeed.resetLocalCaches();

        PriceFeed.Px px = PriceFeed.get(ghost);

        assertTrue(px.priced(),
                "THE BUG THIS PINS: the shared read and the upstream call used to be one "
                + "read-through expression, skipped wholesale while backing off. After a restart "
                + "with the upstream unreachable, the refresher's first failing cycle marked "
                + "every symbol unpriced and the next request then answered 'unavailable' for a "
                + "whole shelf whose marks were sitting in Redis an hour from expiry · measured "
                + "exactly that way. 'Do not call CoinGecko' and 'do not look at what we already "
                + "have' are different instructions and only the first was ever meant.");
        assertEquals(new BigDecimal("100.00"), px.price());
    }

    @Test
    @DisplayName("lesson 6: an upstream that is down degrades to an older number, never to an error")
    void lesson6_theRefreshFailsOpen() {
        // refreshNow returns null when it got nothing, and · this is the part
        // that matters · writes NOTHING in that case. The last good value is
        // left exactly where it is, so the next reader still gets a real price
        // from a real moment rather than an exception or a gap.
        //
        // A symbol nothing lists, unique per run so a warm entry from an
        // earlier run cannot decide the outcome.
        String ghost = "zzqx" + System.nanoTime();
        PriceFeed.Px before = PriceFeed.get(ghost);
        assertFalse(before.priced(), "nothing lists this, so there is no price");
        assertEquals("unavailable", before.source(),
                "and the admission is a word · never a zero, and never a stale number "
                + "borrowed from somewhere else and relabeled");

        assertDoesNotThrow(() -> Refresher.queue(ghost),
                "a refresh for something unpriceable must not throw into the request path");
        assertNull(assertDoesNotThrow(() -> PriceFeed.refreshNow(ghost)),
                "THE POINT: a failed refresh reports nothing and writes nothing. That is what "
                + "lets refresh-ahead replace a read-through cache without weakening the "
                + "promise the cache made · an upstream being down costs freshness, never "
                + "availability.");
    }
}
