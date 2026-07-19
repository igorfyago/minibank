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
    public record Px(BigDecimal price, BigDecimal usd, String source, BigDecimal prevClose) {

        /** The shape this record had before prevClose existed. */
        public Px(BigDecimal price, BigDecimal usd, String source) {
            this(price, usd, source, null);
        }

        /** Whether there is a number here at all · an unpriced symbol is not a free one. */
        public boolean priced() {
            return price != null && price.signum() > 0;
        }
    }

    private static final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(3)).build();
    private static final Map<String, Object[]> cache = new ConcurrentHashMap<>(); // sym -> [Px, fetchedAtMillis]

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

    private static boolean backingOff(String symbol) {
        Long t = unpricedAt.get(symbol);
        return t != null && System.currentTimeMillis() - t < UNPRICED_BACKOFF_MS;
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

    public static Px get(String symbol) {
        // L1: in-process (a single pod serving the same price for 60s).
        Object[] hit = cache.get(symbol);
        if (hit != null && System.currentTimeMillis() - (long) hit[1] < 60_000) return (Px) hit[0];
        // L2: Redis · SHARED across every pod, so one call to CoinGecko/Yahoo
        // warms the price for the whole fleet, not once per pod.
        // the loader returns null on upstream failure · a failed price is NOT
        // cached, so a recovered feed is picked up on the next call instead of
        // a stale fallback being pinned for 60s across the whole fleet
        // the cache namespace is versioned: the encoding grew a fourth field
        // and a half-rolled fleet reading the old three-field form would
        // silently serve a null prevClose for 60s
        String enc = backingOff(symbol) ? null : Cache.getOrLoad("prices:live2", symbol, 60, () -> {
            try {
                // counted HERE, inside the loader, because this is the only
                // line in the class that reaches the internet · Redis serving
                // the answer means the loader never runs and nothing upstream
                // was asked
                upstreamAttempts.incrementAndGet();
                Px f = fetch(symbol);
                // f.source(), NOT the literal "live". An equity mark converted
                // at a stale FX rate comes back from fetch() as 'cached', and
                // hardcoding the label here overwrote that admission on its
                // way into the shared cache · where the whole fleet would then
                // read it as live for the next 60 seconds.
                return f.price().toPlainString() + '|' + f.usd().toPlainString() + '|' + f.source() + '|'
                        + (f.prevClose() == null ? "" : f.prevClose().toPlainString());
            } catch (Exception e) { return null; }
        });
        // the ATTEMPT is recorded, never the answer · see UNPRICED_BACKOFF_MS.
        // put and not putIfAbsent: the window has to restart on each failed
        // attempt, or the first timestamp ages out and never refreshes, and
        // the backoff silently stops backing off.
        if (enc == null) { if (!backingOff(symbol)) unpricedAt.put(symbol, System.currentTimeMillis()); }
        else unpricedAt.remove(symbol);
        Px px;
        if (enc != null) {
            px = decode(enc, symbol);
        } else if (hit != null) {          // upstream down: last good price, honestly labeled
            Px last = (Px) hit[0];
            px = new Px(last.price(), last.usd(), "cached", last.prevClose());
        } else {
            px = unavailable();
        }
        // AN ADMISSION IS NOT AN OBSERVATION, so it does not go in the cache.
        //
        // Storing it would pin "I do not know" for 60 seconds and delay the
        // recovery it is meant to be temporary about. Worse, it would become
        // the `hit` that the branch above reads on the NEXT failure, and that
        // branch relabels whatever it finds as 'cached' · which is how an
        // invented price used to launder itself into looking like a real one
        // that was merely old. Only a price we actually saw is worth keeping.
        if (px.priced()) cache.put(symbol, new Object[]{px, System.currentTimeMillis()});
        return px;
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

    private static Px decode(String enc, String symbol) {
        try {
            String[] p = enc.split("\\|", -1);          // -1: keep a trailing empty prevClose
            BigDecimal prev = p.length > 3 && !p[3].isBlank() ? new BigDecimal(p[3]) : null;
            return new Px(new BigDecimal(p[0]), new BigDecimal(p[1]), p[2], prev);
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
