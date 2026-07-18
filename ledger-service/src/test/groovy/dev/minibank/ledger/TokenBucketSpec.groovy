package dev.minibank.ledger

import spock.lang.Specification

/**
 * A Spock specification · the rate limiter, described the way Spock reads best:
 * given / when / then, and a data table for the refill maths. Same TokenBucket
 * the live edge runs; Spock is just a second, more expressive test voice
 * alongside JUnit (both run under one `mvn test`).
 */
class TokenBucketSpec extends Specification {

    static final long SEC = 1_000_000_000L

    def "a fresh bucket allows exactly its burst, then refuses"() {
        given: "a bucket of 3 tokens that refills at 1/sec, at t=0"
        def bucket = new TokenBucket(3, 1, 0)

        expect: "the first three takes pass and the fourth is refused, no time having passed"
        bucket.take(0)
        bucket.take(0)
        bucket.take(0)
        !bucket.take(0)
    }

    def "tokens refill over time at the configured rate"() {
        given: "a drained bucket (capacity 2, 1 token/sec)"
        def bucket = new TokenBucket(2, 1, 0)
        bucket.take(0)
        bucket.take(0)

        expect: "after #elapsedSec seconds, a take is #allowed"
        bucket.take(elapsedSec * SEC) == allowed

        where:
        elapsedSec | allowed
        0          | false   // still empty
        1          | true    // one token refilled
    }

    def "refill is capped at capacity, so idle time cannot bank infinite tokens"() {
        given: "a capacity-2 bucket left idle for a long time"
        def bucket = new TokenBucket(2, 1, 0)

        when: "we wait an hour, then drain"
        long t = 3600 * SEC

        then: "only capacity-many takes succeed, not 3600"
        bucket.take(t)
        bucket.take(t)
        !bucket.take(t)
    }
}
