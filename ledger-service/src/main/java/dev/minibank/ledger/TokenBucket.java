package dev.minibank.ledger;

/**
 * A token bucket, the deep-dive favorite: `capacity` tokens allow a burst,
 * refilled continuously at `ratePerSec`. Empty bucket = HTTP 429 · the
 * edge slows abusive callers down BEFORE they reach the ledger.
 *
 * Pairs with idempotency, and the pairing is the whole point:
 * idempotency makes retries SAFE, rate limiting makes them CHEAP.
 *
 * This one is per-instance and in-memory; a fleet shares state at the
 * gateway or in Redis · same algorithm, different address. Time comes in
 * as a parameter so the mechanism is testable without sleeping.
 */
public final class TokenBucket {

    private final double capacity;
    private final double ratePerSec;
    private double tokens;
    private long lastNanos;

    public TokenBucket(double capacity, double ratePerSec, long nowNanos) {
        this.capacity = capacity;
        this.ratePerSec = ratePerSec;
        this.tokens = capacity;
        this.lastNanos = nowNanos;
    }

    public synchronized boolean take(long nowNanos) {
        tokens = Math.min(capacity, tokens + (nowNanos - lastNanos) / 1e9 * ratePerSec);
        lastNanos = nowNanos;
        if (tokens < 1) return false;
        tokens -= 1;
        return true;
    }
}
