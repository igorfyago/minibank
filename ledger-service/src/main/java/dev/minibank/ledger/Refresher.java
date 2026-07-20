package dev.minibank.ledger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.Map;

/**
 * REFRESH-AHEAD · the background that pays what the foreground used to.
 *
 * The Products shelf was slow for a reason that no amount of cache tuning
 * reaches: the price of a mark was fetched BECAUSE A CUSTOMER ASKED FOR IT.
 * A read-through cache only ever populates on a miss, so on sparse traffic ·
 * which is what this bank actually gets · every single page load arrived
 * after the last value had expired and paid the full upstream cost in front
 * of the user. Measured: about 540ms locally and roughly two seconds from the
 * EC2 box, of which 95% was CoinGecko, Yahoo and the FX service, in series.
 *
 * This class inverts that. A small fixed set of marks · the instruments the
 * registry lists, plus whatever anyone has asked for since · is refreshed on
 * a schedule whether or not a customer is looking, so a request finds a warm
 * value and never waits on an upstream. The two seconds still get paid; they
 * are paid here, by nobody.
 *
 * THREE THINGS THIS MUST NOT DO, each of which is a way background work
 * normally goes wrong:
 *
 *   1. DIE. A refresher that throws once and leaves a dead thread behind
 *      looks exactly like a working one until the prices quietly stop moving.
 *      Every task body is wrapped; the scheduler is never allowed to see an
 *      exception, because a ScheduledExecutorService silently cancels the
 *      repeat of a task that threw.
 *   2. SPAM. An upstream that is failing must be asked LESS, not more. Each
 *      symbol carries its own consecutive-failure count and backs off
 *      exponentially from the base interval to a ceiling, and recovery resets
 *      it. Across pods, a Redis lease means one refresh per symbol per
 *      interval fleet-wide rather than one per pod.
 *   3. BLOCK ANYTHING. It runs on virtual threads, it never holds a lock a
 *      request path wants, and a failed refresh writes nothing at all · the
 *      last good value stays exactly where it is. That is what makes the
 *      whole arrangement fail open: an upstream being down degrades a price
 *      to a slightly older price, which is the same promise the read-through
 *      cache made and the reason this could replace it safely.
 */
public final class Refresher {

    /** How often a healthy symbol is re-marked. Comfortably inside the 60s
     *  freshness window, so a value is replaced before it is ever stale. */
    static final long INTERVAL_MS = 20_000;

    /** The ceiling a failing symbol backs off to · about ten minutes. */
    static final long MAX_BACKOFF_MS = 600_000;

    private static final Map<String, Integer> failures = new ConcurrentHashMap<>();
    private static final Map<String, Long> nextAttemptAt = new ConcurrentHashMap<>();
    private static volatile ScheduledExecutorService scheduler;
    private static volatile java.util.concurrent.ExecutorService workers;

    private Refresher() {}

    /**
     * Start the loop. Idempotent, and safe to call when nothing else is
     * configured · with no Redis and no upstream this simply fails every
     * cycle, quietly, and the bank behaves exactly as it did before.
     */
    public static synchronized void start() {
        if (scheduler != null) return;
        ThreadFactory vf = Thread.ofVirtual().name("mark-refresh-", 0).factory();
        workers = Executors.newThreadPerTaskExecutor(vf);
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mark-refresh-tick");
            t.setDaemon(true);           // must never hold the JVM open
            return t;
        });
        scheduler.scheduleWithFixedDelay(Refresher::tick, 0, INTERVAL_MS, TimeUnit.MILLISECONDS);
        System.out.println("refresher: refreshing marks every " + (INTERVAL_MS / 1000) + "s");
    }

    public static synchronized void stop() {
        if (scheduler != null) { scheduler.shutdownNow(); scheduler = null; }
        if (workers != null) { workers.shutdownNow(); workers = null; }
        failures.clear();
        nextAttemptAt.clear();
    }

    /**
     * SEED THE TRACKED SET FROM THE REGISTRY, so the very first request after
     * a deploy finds warm values rather than being the thing that warms them.
     *
     * Best effort by construction: a shard that is briefly unreachable at boot
     * must not stop the bank from starting, and it does not need to · the set
     * refills from get() the moment anyone asks for anything.
     */
    public static void seedFromRegistry(Shard shard) {
        try (java.sql.Connection c = shard.open()) {
            for (AssetRegistry.Asset a : AssetRegistry.all(c))
                PriceFeed.track(a.symbol().toLowerCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            System.out.println("refresher: could not seed from the registry (" + e.getMessage()
                    + ") · it will fill in from live traffic instead");
        }
    }

    /**
     * Ask for one symbol to be refreshed behind a response · what
     * stale-while-revalidate calls on its way out of PriceFeed.get.
     *
     * Coalescing is the backoff map doing double duty: a symbol already
     * scheduled sooner than now is simply not queued twice, so a burst of
     * requests for the same stale mark produces ONE upstream call and not one
     * per reader. That property is the reason this is a queue-if-due rather
     * than a plain submit.
     */
    static boolean queue(String symbol) {
        var pool = workers;
        // NOT STARTED MEANS NOT QUEUED, and the caller has to know. Serving a
        // stale mark is only honest if something is actually going to replace
        // it; without a refresher the value would simply get older every time
        // it was read. Saying so lets PriceFeed.get fall back to the blocking
        // load it always did · which is exactly the state every test runs in.
        if (pool == null || pool.isShutdown()) return false;
        if (!due(symbol)) return true;                   // a refresh is already coming
        hold(symbol, INTERVAL_MS);                       // claim it before submitting, so a burst collapses
        try {
            pool.submit(() -> refreshOne(symbol));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** One cycle: every tracked symbol that is due, refreshed in parallel. */
    private static void tick() {
        try {
            var pool = workers;
            if (pool == null || pool.isShutdown()) return;
            for (String symbol : PriceFeed.tracked())
                if (due(symbol)) {
                    hold(symbol, INTERVAL_MS);
                    pool.submit(() -> refreshOne(symbol));
                }
            FxRefresh.queue();
        } catch (Throwable t) {
            // THE WHOLE REASON THIS CATCH IS Throwable. scheduleWithFixedDelay
            // cancels the repetition of a task that throws, permanently and
            // silently · so an exception escaping here would not slow the
            // refresher down, it would end it, and the only symptom would be
            // prices that stopped moving hours ago.
            System.err.println("refresher: tick failed · " + t);
        }
    }

    private static void refreshOne(String symbol) {
        try {
            // ONE POD PER SYMBOL PER INTERVAL, fleet-wide. Without this, three
            // replicas refreshing on the same schedule means three times the
            // upstream load for exactly the same value · and CoinGecko's
            // keyless endpoint rate-limits, which would turn a cache warmer
            // into the reason the feed is down. No Redis means no lease and
            // every pod refreshes its own, which is correct and merely less
            // efficient.
            if (!Cache.lease("refresh", symbol, (int) (INTERVAL_MS / 1000))) return;
            PriceFeed.Px px = PriceFeed.refreshNow(symbol);
            if (px != null && px.priced()) {
                failures.remove(symbol);
                nextAttemptAt.put(symbol, System.currentTimeMillis() + INTERVAL_MS);
                Metrics.inc("minibank_mark_refresh_total", "result=\"ok\"");
            } else {
                backOff(symbol);
                Metrics.inc("minibank_mark_refresh_total", "result=\"failed\"");
            }
        } catch (Throwable t) {
            backOff(symbol);
            Metrics.inc("minibank_mark_refresh_total", "result=\"error\"");
        }
    }

    /**
     * Exponential, bounded, and reset by a single success · the standard
     * shape, applied per symbol rather than per feed. Per symbol because
     * these upstreams fail per symbol: ZZQQ is a 404 forever while AAPL two
     * lines away is fine, and a shared backoff would let one dead ticker
     * slow the refresh of every live one.
     */
    private static void backOff(String symbol) {
        int n = failures.merge(symbol, 1, Integer::sum);
        long wait = Math.min(MAX_BACKOFF_MS, INTERVAL_MS * (1L << Math.min(n, 6)));
        hold(symbol, wait);
    }

    private static boolean due(String symbol) {
        Long at = nextAttemptAt.get(symbol);
        return at == null || System.currentTimeMillis() >= at;
    }

    private static void hold(String symbol, long ms) {
        nextAttemptAt.put(symbol, System.currentTimeMillis() + ms);
    }

    /**
     * THE RATE IS A MARK TOO, and it was the other upstream in the request
     * path · about 70ms of the measured page load, and the leg that every
     * equity price is converted through, so an FX stall stalls AAPL, MSFT and
     * both options at once.
     */
    static final class FxRefresh {
        private static volatile long nextAt;
        private static volatile int failures;

        private FxRefresh() {}

        /**
         * Refresh the rate OFF the calling thread, or say that it could not be.
         *
         * Returns false when there is no running refresher to hand the work
         * to · which is not a detail, it is the contract FxClient depends on.
         * A stale rate served with a refresh that silently never happens is a
         * rate that gets older forever, so the caller has to be able to tell
         * "queued" from "nobody is listening" and do the blocking lookup
         * itself in the second case. That is the state every test runs in, and
         * it is the behaviour this class had before the refresher existed.
         */
        static boolean queue() {
            var pool = workers;
            if (pool == null || pool.isShutdown()) return false;
            if (System.currentTimeMillis() < nextAt) return true;   // already scheduled soon enough
            nextAt = System.currentTimeMillis() + INTERVAL_MS;      // claim before submitting
            try {
                pool.submit(FxRefresh::refresh);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        private static void refresh() {
            long now = System.currentTimeMillis();
            try {
                FxClient.Rate r = FxClient.refreshNow();
                if (r != null && r.observed()) {
                    failures = 0;
                    nextAt = now + INTERVAL_MS;
                    Metrics.inc("minibank_mark_refresh_total", "result=\"fx_ok\"");
                    return;
                }
            } catch (Throwable ignored) {
                // falls through to the backoff · an FX service that throws and
                // one that answers a fallback are the same fact to this loop
            }
            int n = ++failures;
            nextAt = now + Math.min(MAX_BACKOFF_MS, INTERVAL_MS * (1L << Math.min(n, 6)));
            Metrics.inc("minibank_mark_refresh_total", "result=\"fx_failed\"");
        }
    }
}
