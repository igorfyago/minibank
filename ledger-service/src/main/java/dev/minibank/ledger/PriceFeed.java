package dev.minibank.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live prices, or none. BTC from CoinGecko (EUR and USD in one call); AAPL
 * from Yahoo (USD), with euros derived through the FX SERVICE · all keyless
 * public endpoints, cached for 60s, with a `source` flag so the UI never has
 * to lie about freshness.
 * Display is in dollars (what markets quote); the ledger settles euros.
 *
 * EVERY PRICE THIS CLASS RETURNS WAS OBSERVED. There are three outcomes and
 * no fourth: 'live' (just fetched), 'cached' (fetched earlier, upstream is
 * down, still a real price from a real moment), or an unpriced Px whose
 * price() is null. It does not compute a plausible number when it has none ·
 * see unavailable() for why that distinction is the whole design.
 *
 * The price is captured AT EXECUTION and written into the trade's event ·
 * the ledger stores the units and the euros; the ratio IS the price paid.
 */
public final class PriceFeed {

    /**
     * A mark, and what it moved from.
     *
     * prevClose is NULLABLE and that is the whole point of it. It is the
     * previous SESSION close for an equity and the price 24 hours ago for a
     * crypto · two different windows, deliberately not averaged into one
     * fiction. When the feed does not hand one over it stays null, and every
     * caller downstream has to decide what to show instead of a day change.
     * A zero here would render as "flat today", which is a lie with a
     * plausible face on it.
     */
    public record Px(BigDecimal price, BigDecimal usd, String source, BigDecimal prevClose, long asOf) {

        /** The shape this record had before prevClose existed. */
        public Px(BigDecimal price, BigDecimal usd, String source) {
            this(price, usd, source, null, 0L);
        }

        /** The shape it had before the observation time was carried. */
        public Px(BigDecimal price, BigDecimal usd, String source, BigDecimal prevClose) {
            this(price, usd, source, prevClose, 0L);
        }

        /** Whether there is a number here at all · an unpriced symbol is not a free one. */
        public boolean priced() {
            return price != null && price.signum() > 0;
        }

        /**
         * HOW OLD THIS MARK IS, in seconds, or -1 when it never carried a time.
         *
         * The whole reason refresh-ahead is safe to ship. A background loop
         * that keeps values warm will, on a bad day, be serving something it
         * fetched ten minutes ago · and a ten minute old price rendered with
         * no age beside it is the same category of lie as the invented
         * fallback this class already refuses to compute. The number is only
         * honest if the screen can say when it was true.
         */
        public long ageSeconds() {
            return asOf <= 0 ? -1 : Math.max(0, (System.currentTimeMillis() - asOf) / 1000);
        }
    }

    private static final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(3)).build();
    private static final Map<String, Object[]> cache = new ConcurrentHashMap<>(); // sym -> [Px, observedAtMillis]

    /** How long an observation counts as current. Unchanged · this is the
     *  number the screens were always built around. */
    static final long FRESH_MS = 60_000;

    /**
     * How long the SHARED store keeps a value, which is a different question.
     *
     * An hour, against a sixty second freshness window, because the shared
     * copy's job is no longer "is this current" · the encoded timestamp
     * answers that · but "is there anything at all to serve a pod that just
     * booted, or a customer who arrived on a quiet afternoon". Setting these
     * two numbers equal is what made the cache useless; see loadOrFetch.
     */
    static final int STALE_TTL_S = 3600;

    /**
     * The namespace, versioned again. The encoding grew a fifth field and the
     * old four-field form carries no observation time, so a half-rolled fleet
     * reading it would report every mark as ageless · which the screens are
     * now entitled to render as 'live'.
     */
    static final String NS = "prices:live3";

    /**
     * THE SMALL FIXED SET THIS BANK ACTUALLY MARKS.
     *
     * The refresher needs to know what to keep warm, and the honest answer is
     * "whatever anyone has asked for", not a literal list · this codebase has
     * had the two-symbol blind spot surgically removed from four different
     * places already and reintroducing it here as a refresh list would be the
     * fifth. So interest is RECORDED rather than declared: every get() marks
     * its symbol, the registry seeds the set at boot, and the loop refreshes
     * what it finds.
     *
     * Bounded, because an unbounded one is a denial of service wearing a
     * cache's clothes: /api/prices/history takes a symbol from the query
     * string, and without a ceiling anyone could enlist this process into
     * polling a thousand tickers forever.
     */
    private static final int MAX_TRACKED = 256;
    private static final java.util.Set<String> tracked =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    static void track(String symbol) {
        if (symbol != null && tracked.size() < MAX_TRACKED) tracked.add(symbol);
    }

    static java.util.Set<String> tracked() { return java.util.Set.copyOf(tracked); }

    private PriceFeed() {}

    /**
     * WHEN THE FEED SAID NOTHING, STOP ASKING FOR A WHILE.
     *
     * This is a NEGATIVE cache and it is deliberately not the price cache.
     * It holds a timestamp and never a number, so it can never become the
     * `hit` that the upstream-down branch below relabels as 'cached' · which
     * is the exact laundering route unavailable() was written to close, and
     * closing it is why a failed price was never cached at all.
     *
     * Not caching the FACT of the failure, though, turned out to cost more
     * than it saved. An unpriced symbol re-hit upstream on every single call,
     * with a 3s connect and 4s read timeout, and nothing anywhere absorbed
     * it: the shared watchlist prices a hundred and nine tickers, so the
     * desk's rail took upwards of five seconds to answer against a two-second
     * client budget even when every upstream call failed fast. It timed out
     * on every load, forever, and the migration that runs behind it therefore
     * never ran.
     *
     * The window matches the positive TTL exactly, so recovery is detected on
     * the same schedule a price change is. That is the honest cost and it is
     * stated rather than tuned away: a feed that comes back is served up to
     * 60 seconds later than it could have been, and in exchange a feed that
     * is down costs one upstream call per symbol per minute instead of one
     * per symbol per request.
     */
    private static final long UNPRICED_BACKOFF_MS = 60_000;
    private static final Map<String, Long> unpricedAt = new ConcurrentHashMap<>();

    /**
     * How many times this process has actually gone UPSTREAM for a mark.
     *
     * The thing worth counting about a price feed is not how often it is
     * asked · that is a property of the screens · but how often the asking
     * escapes the caches and costs a real request to CoinGecko or Yahoo, with
     * a real timeout attached. That number is what made the shared watchlist
     * unanswerable, it is what the backoff exists to bound, and until now
     * nothing anywhere reported it. It is also the only way to assert the
     * backoff: wall-clock timing cannot tell a cached miss from an upstream
     * one on a machine where the upstream fails instantly.
     */
    private static final java.util.concurrent.atomic.AtomicLong upstreamAttempts =
            new java.util.concurrent.atomic.AtomicLong();

    public static long upstreamAttempts() {
        return upstreamAttempts.get();
    }

    /**
     * The namespace the FACT of a failure lives in · a marker, never a number.
     *
     * Deliberately a different key space from the price cache, so that nothing
     * stored here can ever be read by the branch that relabels a hit as
     * 'cached'. That is the same separation unpricedAt already enforces in
     * process; this is only its shared half.
     */
    private static final String NS_UNPRICED = "prices:unpriced";

    private static boolean backingOff(String symbol) {
        Long t = unpricedAt.get(symbol);
        if (t != null) return System.currentTimeMillis() - t < UNPRICED_BACKOFF_MS;
        // NOT KNOWN LOCALLY IS NOT THE SAME AS NOT KNOWN, and the difference
        // is a whole upstream timeout on the first request after a deploy.
        // A restart empties the in-process map, so a ticker nothing lists ·
        // ZZQQ on this shelf · went upstream again on the first page load and
        // hung there for the full 3s connect timeout while every other mark
        // was served instantly out of Redis. Measured: 3.04s for a shelf whose
        // five other prices were already warm. The shared marker means a
        // restarted pod, or a second pod that never asked, inherits what the
        // fleet already learned.
        return Cache.has(NS_UNPRICED, symbol);
    }

    /**
     * Have we tried this symbol and come back with nothing, ever?
     *
     * Distinct from {@link #backingOff}, which asks the narrower question of
     * whether the failure is recent enough to still be suppressing calls. This
     * one stays true after the window lapses and until a fetch actually
     * succeeds, which is what lets the refresher rather than a customer own
     * the retry for a ticker nothing lists.
     */
    private static boolean knownUnpriceable(String symbol) {
        return unpricedAt.containsKey(symbol) || Cache.has(NS_UNPRICED, symbol);
    }

    /** Record the failure for everyone · the window matches the local one, so
     *  a feed that recovers is still noticed on the same schedule. */
    private static void markUnpriced(String symbol) {
        unpricedAt.put(symbol, System.currentTimeMillis());
        Cache.put(NS_UNPRICED, symbol, (int) (UNPRICED_BACKOFF_MS / 1000), "1");
    }

    private static void clearUnpriced(String symbol) {
        unpricedAt.remove(symbol);
        Cache.invalidate(NS_UNPRICED, symbol);
    }

    /**
     * Drop every in-process cache · the positive one and the backoff.
     *
     * For tests, which need to observe what this class does on a cold start
     * and would otherwise be reading whatever the previous test warmed. It
     * does NOT touch Redis: the shared cache is shared on purpose, and a
     * test that cleared it would be reaching outside its own process to
     * change what another one sees.
     */
    public static void resetLocalCaches() {
        cache.clear();
        unpricedAt.clear();
        histCache.clear();
        // the refresh-ahead set is in-process state too · a test that left it
        // populated would have a background loop re-warming the very symbol
        // the next test is trying to observe on a cold start
        tracked.clear();
    }

    /**
     * MANY MARKS, CONCURRENTLY · the shape a list screen actually asks in.
     *
     * Sequentially this is O(symbols) round trips and there is no request
     * budget it fits inside at rail size. Each get() is independent and
     * already thread-safe (both caches are concurrent, the HttpClient is
     * shared), so the whole batch is one round trip's worth of latency on a
     * virtual thread per symbol · which is what this runtime is for, and the
     * same executor the HTTP server itself runs on.
     *
     * Duplicates collapse before the fan-out, so a list that names a symbol
     * twice costs one call and not two.
     */
    public static Map<String, Px> getAll(java.util.Collection<String> symbols) {
        return fanOut(symbols, PriceFeed::get);
    }

    /**
     * THE FAN-OUT ITSELF · one virtual thread per key, every result collected.
     *
     * Separated from getAll so that the CONCURRENCY can be asserted against a
     * function whose cost a test chooses, rather than against a live feed
     * whose latency it cannot. Timing the real thing does not discriminate:
     * on a machine with no network every upstream call fails in microseconds,
     * so forty sequential calls and forty parallel ones finish equally fast
     * and a wall-clock assertion passes whether or not the fan-out is there.
     * With a seam, a test can hand this a function that sleeps and watch the
     * difference that actually matters in production, where the calls are
     * slow and there are a hundred of them.
     *
     * A key whose function throws is simply absent from the result. One symbol
     * that blew up is one gap on the screen, not a failed page, and every
     * caller already renders a gap for anything missing.
     */
    public static <T> Map<String, T> fanOut(java.util.Collection<String> keys,
                                            java.util.function.Function<String, T> of) {
        Map<String, T> out = new ConcurrentHashMap<>();
        java.util.Set<String> distinct = new java.util.LinkedHashSet<>(keys);
        if (distinct.isEmpty()) return out;
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (String k : distinct)
                pool.submit(() -> {
                    try {
                        T v = of.apply(k);
                        if (v != null) out.put(k, v);
                    } catch (Exception ignored) {
                        // see the docstring · absent, not fatal
                    }
                });
        }   // close() waits for every task, so the map is complete on return
        return out;
    }

    /**
     * A MARK, WITHOUT EVER WAITING ON THE INTERNET · unless there is nothing
     * at all to serve.
     *
     * This method used to be the whole cost of the Products shelf. It checked
     * an in-process value with a 60 second life, and on the 61st second it
     * went to CoinGecko or Yahoo with the customer's request still open. The
     * shelf holds six instruments, portfolio() asked for them one at a time,
     * and the page therefore paid five upstream round trips in series · about
     * 540ms on a developer's machine and roughly two seconds from the EC2 box,
     * on every load that arrived more than a minute after the last one. Which
     * is to say: on nearly every real load, because real traffic here is
     * sparse.
     *
     * The rule now is that a customer's request never blocks on an upstream it
     * has an answer for. There are three cases and the first two never touch
     * the network:
     *
     *   FRESH   observed inside FRESH_MS · served, exactly as before.
     *   STALE   observed longer ago than that · served IMMEDIATELY anyway,
     *           carrying its true age, and a refresh is queued behind the
     *           response. This is stale-while-revalidate, the same doctrine
     *           the front end's swrCache already applies one tier up.
     *   COLD    nothing has ever been observed for this symbol · and there is
     *           no honest way to answer instantly, so this one call does block.
     *           {@link Refresher} exists to make it vanishingly rare: the
     *           tracked set is refreshed on a schedule whether or not anyone
     *           asked, so by the time a customer arrives the value is already
     *           there.
     *
     * The cold branch is deliberately NOT made asynchronous. Returning
     * 'unavailable' for a symbol we simply have not got around to fetching
     * would be a feed-is-down claim on a day the feed is fine · the same
     * category of dishonesty as the fallback price, arriving from the other
     * direction. If we have nothing, we go and get it.
     */
    public static Px get(String symbol) {
        track(symbol);
        Object[] hit = cache.get(symbol);
        if (hit != null) {
            Px last = (Px) hit[0];
            long observedAt = (long) hit[1];
            if (System.currentTimeMillis() - observedAt < FRESH_MS) {
                Metrics.inc("minibank_mark_serve_total", "result=\"fresh\"");
                return last;
            }
            // STALE, AND SERVED ANYWAY · but only if something is actually
            // going to replace it. A stale value handed out with a refresh
            // that nobody performs is a price that ages forever, so when
            // there is no refresher running this falls back to the blocking
            // load it always did.
            if (Refresher.queue(symbol)) {
                Metrics.inc("minibank_mark_serve_total", "result=\"stale\"");
                return stale(last, observedAt);
            }
            return loadOrFetch(symbol);   // counts the serve itself · see below
        }
        // NOTHING IN THIS PROCESS · the shared store may still have it, and
        // only if that misses too is anyone going upstream. loadOrFetch counts
        // which of those happened.
        return loadOrFetch(symbol);
    }

    /**
     * The blocking path · shared store first, then upstream. This is what
     * get() used to do on every miss and now does only when nothing is known.
     */
    private static Px loadOrFetch(String symbol) {
        Object[] hit = cache.get(symbol);
        // L2: Redis · SHARED across every pod, so one call to CoinGecko/Yahoo
        // warms the price for the whole fleet, not once per pod.
        //
        // THE TTL IS NOT THE FRESHNESS WINDOW, and that distinction is the
        // whole repair. This cache used to be written with a 60 second TTL by
        // a caller whose own in-process copy also lived exactly 60 seconds,
        // and the in-process copy was consulted FIRST. So Redis was only ever
        // asked at the precise moment its own entry had expired: the key was
        // written at T with a life to T+60, and the first request that could
        // possibly have needed it arrived at T+60. A single pod could not hit
        // its own write even once. Measured against the running stack the
        // counter read 15 lookups, 15 misses, 0 hits · and in production the
        // 0-18% the X-ray showed was only ever the chance that a DIFFERENT pod
        // had warmed the key recently, which is why it fell to zero whenever
        // traffic was sparse or a pod was alone.
        //
        // Redis now holds the last good value for an hour and the ENCODED
        // TIMESTAMP decides whether it is fresh. Expiry is a garbage-collection
        // policy; freshness is a property of the observation. Conflating the
        // two is what made a shared cache that never shared anything.
        // THE SHARED STORE IS READ UNCONDITIONALLY, and the backoff gates only
        // the upstream call below it.
        //
        // These were one expression · a read-through whose whole operation was
        // skipped while backing off · and that cost real prices. After a
        // restart with the upstream unreachable, the refresher's first cycle
        // fails for every symbol and marks them all unpriced; the next request
        // then found itself backing off and never looked in Redis at all, so a
        // shelf whose five marks were sitting in the shared store an hour from
        // expiry rendered as five 'unavailable' rows. Measured exactly that
        // way before this line was split: every holding unpriced, on a stack
        // whose Redis was full of good values.
        //
        // "Do not call CoinGecko right now" and "do not look at what the fleet
        // already fetched" are different instructions, and only the first one
        // was ever intended.
        String enc = Cache.get(NS, symbol);
        if (enc != null) {
            Metrics.inc("minibank_mark_serve_total", "result=\"stale\"");
        } else if (knownUnpriceable(symbol) && Refresher.queue(symbol)) {
            // A SYMBOL WE HAVE ALREADY FAILED TO PRICE is not worth a
            // customer's time to re-attempt.
            //
            // The measurement that found this: after refresh-ahead removed
            // every other upstream call from the request path, the shelf still
            // spent 40-60ms on some loads, and the probe put all of it in one
            // Yahoo request for ZZQQ · a ticker nothing lists. An unpriceable
            // symbol never lands in the value cache, so it reached here on
            // every request, and whenever its backoff had just lapsed the
            // unlucky customer paid the retry. The backoff bounded how OFTEN
            // that happened and never moved it off the request path.
            //
            // The retry still has to happen · a delisted ticker can come back
            // and a new listing has to start working without a restart · but
            // it is background work by nature, because nobody is waiting on
            // the answer 'still nothing'. So the refresher owns it.
            //
            // THIS TEST SITS BELOW THE SHARED READ, and that ordering is the
            // whole correctness of it. It was above, and the result was that a
            // restart with the upstream unreachable rendered every holding as
            // 'unavailable' while Redis held five good marks an hour from
            // expiry · the refresher's first failing cycle marked them all
            // unpriced, and this branch then answered on their behalf without
            // ever looking. Known-unpriceable is a reason not to CALL anyone;
            // it was never a reason not to look at what we already have.
            Metrics.inc("minibank_mark_serve_total", "result=\"stale\"");
        } else if (!backingOff(symbol)) {
            Metrics.inc("minibank_mark_serve_total", "result=\"cold\"");
            enc = encode(fetchUpstream(symbol));
            if (enc != null) Cache.put(NS, symbol, STALE_TTL_S, enc);
        } else {
            Metrics.inc("minibank_mark_serve_total", "result=\"stale\"");
        }
        // the ATTEMPT is recorded, never the answer · see UNPRICED_BACKOFF_MS.
        // put and not putIfAbsent: the window has to restart on each failed
        // attempt, or the first timestamp ages out and never refreshes, and
        // the backoff silently stops backing off.
        if (enc == null) { if (!backingOff(symbol)) markUnpriced(symbol); }
        else clearUnpriced(symbol);
        Px px;
        if (enc != null) {
            px = decode(enc, symbol);
        } else if (hit != null) {          // upstream down: last good price, honestly labeled
            Px last = (Px) hit[0];
            px = stale(last, (long) hit[1]);
        } else {
            px = unavailable();
        }
        // AN ADMISSION IS NOT AN OBSERVATION, so it does not go in the cache.
        //
        // Storing it would pin "I do not know" and delay the recovery it is
        // meant to be temporary about. Worse, it would become the `hit` that
        // the branch above reads on the NEXT failure, and that branch relabels
        // whatever it finds as 'cached' · which is how an invented price used
        // to launder itself into looking like a real one that was merely old.
        // Only a price we actually saw is worth keeping.
        if (px.priced()) remember(symbol, px);
        return px;
    }

    /**
     * GO UPSTREAM AND PUBLISH, bypassing the shared read.
     *
     * The refresher cannot use loadOrFetch: read-through would hand it back
     * the very Redis value it is trying to replace, and with an hour-long TTL
     * the fleet would settle into serving one increasingly ancient price
     * forever while congratulating itself on a 100% hit rate. A refresh is by
     * definition the one operation that must not be satisfied by the cache.
     *
     * Returns null when the upstream had nothing, and writes NOTHING in that
     * case · a failed refresh must leave the last good value exactly where it
     * is, which is what makes this fail open.
     */
    static Px refreshNow(String symbol) {
        String enc = encode(fetchUpstream(symbol));
        if (enc == null) {
            markUnpriced(symbol);
            return null;
        }
        clearUnpriced(symbol);
        Cache.put(NS, symbol, STALE_TTL_S, enc);
        Px px = decode(enc, symbol);
        if (px.priced()) remember(symbol, px);
        return px;
    }

    /** The one place in this class that reaches the internet. */
    private static String encode(Px f) {
        if (f == null) return null;
        return f.price().toPlainString() + '|' + f.usd().toPlainString() + '|' + f.source() + '|'
                + (f.prevClose() == null ? "" : f.prevClose().toPlainString()) + '|' + f.asOf();
    }

    private static Px fetchUpstream(String symbol) {
        try {
            // counted HERE because this is the only line in the class that
            // reaches the internet · Redis serving the answer means this never
            // runs and nothing upstream was asked
            upstreamAttempts.incrementAndGet();
            Px f = fetch(symbol);
            // f.source(), NOT the literal "live". An equity mark converted at
            // a stale FX rate comes back from fetch() as 'cached', and
            // hardcoding the label here overwrote that admission on its way
            // into the shared cache · where the whole fleet would then read it
            // as live.
            return new Px(f.price(), f.usd(), f.source(), f.prevClose(), System.currentTimeMillis());
        } catch (Exception e) { return null; }
    }

    private static void remember(String symbol, Px px) {
        cache.put(symbol, new Object[]{px, px.asOf() > 0 ? px.asOf() : System.currentTimeMillis()});
    }

    /**
     * The same observation, relabeled as what it now is.
     *
     * 'cached' is the word this class has always used for a real price with an
     * old timestamp, and the age rides along so nothing downstream has to
     * guess how old 'old' is.
     */
    private static Px stale(Px last, long observedAt) {
        return new Px(last.price(), last.usd(), "cached", last.prevClose(), observedAt);
    }

    /**
     * THERE IS NO LAST RESORT. This says "I do not know", and that is the
     * entire body of the method.
     *
     * It used to hand back a hardcoded 90,000 for bitcoin and 195 for Apple
     * whenever the upstream was unreachable, tagged 'fallback' so the screen
     * could grey it out. Two things were wrong with that, and only the second
     * one is interesting.
     *
     * The obvious one: a label is not consent. A number rendered at the size
     * of a balance is read as a balance, and a customer who does not know what
     * the word 'fallback' means beside their portfolio total has been told
     * their bitcoin is worth 90,000 euros. The disclaimer travelled with the
     * number in the JSON and lost to it on the screen every time.
     *
     * The one that actually decided it: the number was never TRUE at any
     * moment. A stale price is a real observation with an old timestamp · it
     * was the price once, and saying so is a fact about the past. An invented
     * price is a fact about nothing. That is the difference between the two
     * branches above this one and this branch, and it is why the 'cached'
     * branch survives and this one does not compute anything.
     *
     * So there are now exactly two honest answers to "what is this worth":
     * the last price we really saw, labeled 'cached', or this · nothing, and
     * every caller downstream has to decide what to render instead of a
     * figure. An unpriced holding is not a worthless one, and the callers that
     * matter (Portfolio.build, the ledger's product shelf) already withhold a
     * total rather than partly summing it.
     */
    private static Px unavailable() {
        return new Px(null, null, "unavailable", null);
    }

    /** package-visible so the freshness rule can be asserted against an
     *  encoded value a test chose, rather than against whatever the internet
     *  happened to be doing while the suite ran */
    static Px decode(String enc, String symbol) {
        try {
            String[] p = enc.split("\\|", -1);          // -1: keep a trailing empty prevClose
            BigDecimal prev = p.length > 3 && !p[3].isBlank() ? new BigDecimal(p[3]) : null;
            long asOf = p.length > 4 && !p[4].isBlank() ? Long.parseLong(p[4]) : 0L;
            // A SHARED VALUE IS OLDER THAN THIS POD, so its age decides its
            // label rather than the fact that we just read it. Reading a
            // fifty-minute-old entry out of Redis and calling it 'live'
            // because the read was fast is the same error as the equity leg
            // that used to be labeled live after a stale FX conversion.
            if (asOf > 0 && System.currentTimeMillis() - asOf >= FRESH_MS)
                return new Px(new BigDecimal(p[0]), new BigDecimal(p[1]), "cached", prev, asOf);
            return new Px(new BigDecimal(p[0]), new BigDecimal(p[1]), p[2], prev, asOf);
        } catch (Exception e) {
            return unavailable();
        }
    }

    private static Px fetch(String symbol) throws Exception {
        if ("btc".equals(symbol)) {
            String body = getBody("https://api.coingecko.com/api/v3/simple/price"
                    + "?ids=bitcoin&vs_currencies=eur,usd&include_24hr_change=true");
            BigDecimal eur = extract(body, "\"eur\"\\s*:\\s*([0-9.]+)");
            BigDecimal usd = extract(body, "\"usd\"\\s*:\\s*([0-9.]+)");
            // crypto never closes, so there is no close to quote · what the
            // feed does give is the move over the last 24h, and the price
            // that implies is a real price from a real moment
            BigDecimal prev = null;
            try {
                BigDecimal pct = extract(body, "\"eur_24h_change\"\\s*:\\s*(-?[0-9.]+)");
                BigDecimal factor = BigDecimal.ONE.add(pct.movePointLeft(2));
                if (factor.signum() > 0) prev = eur.divide(factor, 8, RoundingMode.HALF_UP);
            } catch (Exception ignored) {
                // no change field, no day move · null is the honest answer
            }
            return new Px(eur, usd, "live", prev);
        }
        // Equities: Yahoo quotes USD; the euros come through the FX service.
        // The ticker is the SYMBOL, not a constant · this used to hardcode
        // AAPL, which meant every non-bitcoin symbol was served Apple's price.
        String chart = getBody("https://query1.finance.yahoo.com/v8/finance/chart/"
                + URLEncoder.encode(symbol.toUpperCase(Locale.ROOT), StandardCharsets.UTF_8)
                + "?range=1d&interval=1d");
        BigDecimal usd = extract(chart, "\"regularMarketPrice\"\\s*:\\s*([0-9.]+)");
        BigDecimal prevUsd = null;
        try {
            // the payload calls it chartPreviousClose · there is no
            // 'previousClose' key in the range=1d response, verified against
            // a live body rather than assumed
            prevUsd = extract(chart, "\"chartPreviousClose\"\\s*:\\s*([0-9.]+)");
        } catch (Exception ignored) {
            // no prior close in this payload · the day change goes unreported
        }
        return toEur(usd, prevUsd, FxClient.usdToEur());
    }

    /**
     * The currency leg, where the fabrication survived.
     *
     * fetch() used to take FxClient.usdToEur().rate() and throw the
     * accompanying .source() away, then label the result 'live'. When the
     * fx-service is unreachable and nothing was ever cached, that rate is
     * FxClient's hardcoded 0.88 · so the EUR figure was a real USD price
     * multiplied by an invented number, shipped to the UI tagged as live, with
     * nothing downstream able to badge it. This class's own docstring says the
     * fabrication was removed; it had only been removed from the price leg.
     *
     * There is no honest EUR mark without an observed rate, so there is no EUR
     * mark: this raises, and get() falls through to the last real price or to
     * nothing at all. That is the same rule unavailable() already applies one
     * field over, and the reason it has to be a throw rather than a label is
     * that a label loses to a number every single time it is rendered.
     *
     * A rate that was quoted earlier and is merely old is a different case and
     * survives · but it makes the whole mark 'cached', because a fresh USD
     * price converted at yesterday's rate is not a live EUR price and saying
     * so would overstate the weaker leg.
     */
    static Px toEur(BigDecimal usd, BigDecimal prevUsd, FxClient.Rate fx) {
        if (!fx.observed())
            throw new IllegalStateException("no observed USD to EUR rate · refusing to invent a EUR price");
        BigDecimal rate = fx.rate();
        String source = "live".equals(fx.source()) ? "live" : "cached";
        // BOTH legs converted with the SAME rate, deliberately. Converting the
        // mark now and the close on some other call's rate would smuggle FX
        // drift into a number the screen labels as the stock's day move.
        return new Px(usd.multiply(rate).setScale(2, RoundingMode.HALF_UP), usd, source,
                prevUsd == null ? null : prevUsd.multiply(rate).setScale(2, RoundingMode.HALF_UP));
    }

    private static final Map<String, Object[]> histCache = new ConcurrentHashMap<>();

    /** ~30 days of real prices as [[ms,eur],...] JSON · cached 10 min,
     *  thinned to ≤120 points, empty array when the feed is down. */
    public static String historyJson(String symbol) {
        // read-through Redis: 30 days of prices is a heavy upstream call and
        // perfectly stale-tolerant · the textbook thing to cache. Falls back
        // to the in-process cache when Redis is absent.
        return Cache.getOrLoad("prices:history", symbol, 600, () -> {
            Object[] hit = histCache.get(symbol);
            if (hit != null && System.currentTimeMillis() - (long) hit[1] < 600_000) return (String) hit[0];
            String out;
            try {
                out = "btc".equals(symbol) ? btcHistory() : equityHistory(symbol);
            } catch (Exception e) {
                System.err.println("pricefeed history " + symbol + ": " + e);
                out = hit != null ? (String) hit[0] : "[]";
            }
            histCache.put(symbol, new Object[]{out, System.currentTimeMillis()});
            return out;
        });
    }

    private static String btcHistory() throws Exception {
        String body = getBody("https://api.coingecko.com/api/v3/coins/bitcoin/market_chart?vs_currency=eur&days=30&interval=daily");
        // the response carries prices, market_caps AND total_volumes ·
        // scan ONLY the prices array or the chart plots market caps
        int i = body.indexOf("\"prices\"");
        int j = body.indexOf("]]", i);
        if (i < 0 || j < 0) throw new IllegalStateException("no prices array");
        body = body.substring(i, j + 2);
        Matcher m = Pattern.compile("\\[(\\d+)(?:\\.\\d+)?,([0-9.]+)\\]").matcher(body);
        return thin(m);
    }

    /** The same fallthrough lived here: the URL named AAPL whatever you asked for. */
    private static String equityHistory(String symbol) throws Exception {
        String chart = getBody("https://query1.finance.yahoo.com/v8/finance/chart/"
                + URLEncoder.encode(symbol.toUpperCase(Locale.ROOT), StandardCharsets.UTF_8)
                + "?range=1mo&interval=1d");
        // the same rule as toEur: 30 days of prices converted at a rate nobody
        // ever quoted is 30 days of invented prices, and a chart is no more
        // entitled to make one up than a mark is
        FxClient.Rate fx = FxClient.usdToEur();
        if (!fx.observed())
            throw new IllegalStateException("no observed USD to EUR rate · refusing to invent a EUR series");
        BigDecimal rate = fx.rate();
        Matcher ts = Pattern.compile("\"timestamp\":\\[([0-9,]+)\\]").matcher(chart);
        Matcher cl = Pattern.compile("\"close\":\\[([0-9.,null ]+)\\]").matcher(chart);
        if (!ts.find() || !cl.find()) throw new IllegalStateException("no series");
        String[] times = ts.group(1).split(",");
        String[] closes = cl.group(1).split(",");
        StringBuilder b = new StringBuilder("[");
        boolean first = true;
        for (int i = 0; i < Math.min(times.length, closes.length); i++) {
            if (closes[i].contains("null") || closes[i].isBlank()) continue;
            if (!first) b.append(',');
            first = false;
            BigDecimal eur = new BigDecimal(closes[i].trim()).multiply(rate).setScale(2, RoundingMode.HALF_UP);
            b.append('[').append(times[i].trim()).append("000,").append(eur.toPlainString()).append(']');
        }
        return b.append(']').toString();
    }

    private static String thin(Matcher m) {
        java.util.List<String> pts = new java.util.ArrayList<>();
        while (m.find()) pts.add("[" + m.group(1) + "," + m.group(2) + "]");
        int step = Math.max(1, pts.size() / 120);
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < pts.size(); i += step) {
            if (b.length() > 1) b.append(',');
            b.append(pts.get(i));
        }
        return b.append(']').toString();
    }

    private static String getBody(String url) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(4))
                        .version(java.net.http.HttpClient.Version.HTTP_1_1)
                        .header("User-Agent", "Mozilla/5.0 (minibank-demo)")
                        .header("Accept", "application/json,text/*").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IllegalStateException("HTTP " + r.statusCode());
        return r.body();
    }

    private static BigDecimal extract(String body, String regex) {
        Matcher m = Pattern.compile(regex).matcher(body);
        if (!m.find()) throw new IllegalStateException("price not found");
        return new BigDecimal(m.group(1));
    }
}
