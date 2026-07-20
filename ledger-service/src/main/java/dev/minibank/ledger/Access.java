package dev.minibank.ledger;

import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WHOSE ACCOUNT IS PRIVATE · the rule that closes the URL hole.
 *
 * Every customer-facing route on this bank takes a customer id from the query
 * string or the body. For as long as that number was the ONLY answer to "who
 * is asking", changing it in the address bar read somebody else's book. That
 * is the whole vulnerability, and it is not theoretical: bank.b4rruf3t.com is
 * on the public internet and ?customer=10 is one keystroke from ?customer=11.
 *
 * THE RULE, in one line: AN ACCOUNT NOBODY HAS CLAIMED IS PUBLIC, AND AN
 * ACCOUNT SOMEBODY HAS CLAIMED IS THEIRS.
 *
 * Claiming means a row in sso_customers · an SSO subject bound to a customer
 * id. So the estate today splits itself, with no list to maintain:
 *
 *   10 igor    claimed     private · only igor's token opens it
 *   11 coco    unclaimed   public  · the demo, explorable by anyone
 *   12 oscar   unclaimed   public
 *   13 luna    unclaimed   public
 *   14 abel    unclaimed   public
 *
 * WHY NOT AN ALLOWLIST OF DEMO IDS, which is the obvious way to write this.
 * Because /api/signup mints ids from MAX+1, so the next visitor to press the
 * button becomes 15, lands outside any fixed list, and is refused their own
 * brand new account · by a rule whose entire purpose was to protect it. A
 * hardcoded list also has to be edited by a human on the day a real person
 * signs up, and a security boundary that depends on somebody remembering to
 * edit it is a boundary that is one busy afternoon from being wrong.
 *
 * Deriving it from the link inverts that. A new account is born public and
 * becomes private the instant somebody proves it is theirs, which is the same
 * moment they gain the means to open it. There is no window in which an
 * account is private to nobody, and none in which a real user's account is
 * public.
 *
 * WHAT THIS DELIBERATELY DOES NOT DO. It does not make the demo read-only.
 * The unclaimed accounts are a sandbox of invented euros whose entire purpose
 * is to be poked at by strangers, and turning the front page into a museum
 * would be a cost with no matching risk. Move fake money between fake people
 * all you like. You cannot touch a claimed one at all, in either direction,
 * because the debit side of /api/transfer resolves through the same caller()
 * seam every read does.
 *
 * FAIL CLOSED, and only here. If the directory cannot be reached we do not
 * know whether an account is claimed, and serving it on the assumption that it
 * is not would reopen the hole precisely when nobody can see the logs. Callers
 * get "cannot verify" rather than data. The cost of that stance is bounded to
 * approximately nothing, because the routing lookup in Directory.shardOf goes
 * to the same database and already throws when it is down · a request refused
 * here was a request about to fail anyway, one layer further in and less
 * honestly.
 */
public final class Access {

    /**
     * The set of claimed ids, held in memory rather than asked per request.
     *
     * It has to be cached and the reason is the same one Directory.customerForSso
     * documents: openOwnDb() is a bare DriverManager connect with no pool, so a
     * lookup on every request would open a TCP connection per request and run
     * the directory out of them on the first busy afternoon.
     *
     * A whole-set snapshot rather than a per-id cache, because the set is tiny
     * (one row today, and it is bounded by the number of humans who have ever
     * signed in), it changes approximately never, and holding all of it means a
     * directory outage cannot make a warm process start guessing.
     *
     * null means NEVER SUCCESSFULLY LOADED, which is the one state that fails
     * closed. An empty set is different and perfectly normal: it means we
     * looked and nobody has claimed anything.
     */
    private static volatile Set<Long> claimed = null;
    private static volatile long loadedAt = 0L;

    /**
     * Epoch 0, not Long.MIN_VALUE, and this is not a stylistic preference.
     *
     * The identical gate written the obvious way one module over
     * (dev.b4rruf3t.sso.client.Jwks) seeded its stamp with Long.MIN_VALUE, and
     * `now - Long.MIN_VALUE` overflows a long to a negative number that is
     * always below any interval · so the first refresh never ran, the cache
     * never filled, and every token on the estate failed validation. Zero is a
     * real timestamp in the distant past, so the first attempt always fires.
     */
    private static final AtomicLong lastAttempt = new AtomicLong(0L);

    /** How long a good snapshot is trusted before it is refreshed. */
    private static final long TTL_SECONDS = 60;

    /** How often a FAILING load may be retried · slow enough not to stampede. */
    private static final long RETRY_SECONDS = 5;

    private Access() {}

    /**
     * Refused, and the two reasons are not the same refusal.
     *
     * unavailable=false is an answer about the caller: this account belongs to
     * somebody and you have not shown that you are them. 403.
     *
     * unavailable=true is an answer about US: we could not reach the directory
     * and therefore cannot say who this account belongs to. 503, because
     * telling a caller "forbidden" when the truth is "our database is down"
     * sends them to re-authenticate against a problem no credential can fix.
     */
    public static final class Denied extends RuntimeException {
        public final boolean unavailable;

        Denied(String message, boolean unavailable) {
            super(message);
            this.unavailable = unavailable;
        }
    }

    /**
     * THE GUARD · called on the anonymous path only, from HttpApi.caller and
     * BrokerApi.caller.
     *
     * An identified caller never reaches here, and that asymmetry is the design:
     * a token resolves to its OWN customer id, so an authenticated request
     * cannot name somebody else's account no matter what it puts in the URL.
     * The parameter only survives when nobody was identified, and this is where
     * that survival is made conditional.
     *
     * A null or unparseable id is not this method's problem · it is a 400 from
     * the handler a line later, and refusing it here would turn a malformed
     * request into a security event in the log.
     */
    public static void guard(String requested) {
        Long id = asId(requested);
        if (id == null) return;
        if (isClaimed(id)) {
            throw new Denied("customer " + id + " is a private account", false);
        }
    }

    /** True when this account belongs to an SSO subject. Throws when unknowable. */
    public static boolean isClaimed(long customerId) {
        Set<Long> snapshot = snapshot();
        if (snapshot == null) {
            throw new Denied("cannot verify account ownership · the directory is unreachable", true);
        }
        return snapshot.contains(customerId);
    }

    /**
     * The current set, reloaded when stale, null when it has never been read.
     *
     * A load that FAILS with a good snapshot already in hand keeps serving that
     * snapshot rather than failing closed. Sixty seconds of a stale answer to
     * "has anyone claimed this account" is a far smaller problem than the whole
     * public demo going dark because the directory blinked, and the answer is
     * one that changes when a human signs up rather than continuously.
     */
    private static Set<Long> snapshot() {
        Set<Long> current = claimed;
        long now = System.currentTimeMillis() / 1000;
        boolean stale = current == null || now - loadedAt >= TTL_SECONDS;
        if (stale) {
            long previous = lastAttempt.get();
            long interval = current == null ? RETRY_SECONDS : TTL_SECONDS;
            if (now - previous >= interval && lastAttempt.compareAndSet(previous, now)) {
                try {
                    Set<Long> fresh = Directory.claimedCustomers();
                    claimed = fresh;
                    loadedAt = now;
                    return fresh;
                } catch (SQLException e) {
                    // Keep whatever we had. current==null falls through to the
                    // fail-closed branch in isClaimed, which is the point.
                    System.err.println("access: could not refresh the claimed-account set · " + e);
                }
            }
        }
        return claimed;
    }

    /**
     * The claimed set for a caller that must not fail · null when unreadable.
     *
     * The roster at /api/accounts needs this: it labels every row public or
     * private so the UI can put a lock on what it cannot open, and one
     * unreachable directory should degrade that page to "everything looks
     * private" rather than to no page at all. Every route that DECIDES
     * something goes through guard() instead, which fails closed.
     */
    public static Set<Long> claimedOrNull() {
        return snapshot();
    }

    /** A new link changes the answer immediately, not in up to a minute. */
    public static void invalidate() {
        loadedAt = 0L;
        lastAttempt.set(0L);
    }

    /**
     * Tests: install a known set without a database.
     *
     * Package-visible rather than public · a production caller that could
     * overwrite the claimed set could unclaim an account, which is the one
     * thing this class exists to make impossible.
     */
    static void forTest(Set<Long> ids) {
        long now = System.currentTimeMillis() / 1000;
        claimed = ids;
        loadedAt = ids == null ? 0L : now;
        // Stamped in BOTH cases, and null is the case that needs it. A test
        // installing "we have never successfully loaded" wants the fail-closed
        // branch, not a real connection attempt to a database that is not there
        // · leaving the attempt stamp at zero would send it looking for one.
        lastAttempt.set(now);
    }

    private static Long asId(String requested) {
        if (requested == null || requested.isBlank()) return null;
        try {
            return Long.parseLong(requested.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
