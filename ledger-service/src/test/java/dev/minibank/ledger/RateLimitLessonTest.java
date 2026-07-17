package dev.minibank.ledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE EDGE — a token bucket, proven without sleeping (time is a parameter).
 *
 *   lesson 1  a burst up to capacity passes; the next request is refused
 *   lesson 2  time refills tokens at the configured rate — no more, no less
 *
 * Why it matters for money: idempotency makes retries SAFE; the limiter
 * makes them CHEAP. A client stuck in a retry loop gets 429s at the edge
 * instead of hammering the ledger's locks.
 */
class RateLimitLessonTest {

    private static final long SEC = 1_000_000_000L;

    @Test
    @DisplayName("lesson 1: a burst spends the bucket; the N+1th caller is refused")
    void lesson1_burstThenRefusal() {
        TokenBucket b = new TokenBucket(5, 1, 0);
        for (int i = 0; i < 5; i++) assertTrue(b.take(0), "burst " + i + " fits the bucket");
        assertFalse(b.take(0), "the bucket is empty: 429, not a queue into the database");
    }

    @Test
    @DisplayName("lesson 2: time is the only thing that refills the bucket — at exactly the configured rate")
    void lesson2_refillIsRate() {
        TokenBucket b = new TokenBucket(5, 2, 0);          // 2 tokens per second
        for (int i = 0; i < 5; i++) b.take(0);
        assertFalse(b.take(0));
        assertFalse(b.take(SEC / 4), "a quarter second buys half a token — not enough");
        assertTrue(b.take(SEC), "a full second at 2/s bought the next request");
        assertTrue(b.take(SEC));                            // and the fraction saved up
        assertFalse(b.take(SEC), "spent again — refill is a rate, not a reset");
    }
}
