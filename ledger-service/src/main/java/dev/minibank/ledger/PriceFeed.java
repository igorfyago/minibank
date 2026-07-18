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
 * Live prices, honestly labeled. BTC from CoinGecko (EUR and USD in one
 * call); AAPL from Yahoo (USD), with euros derived through the FX SERVICE
 * · all keyless public endpoints, cached for 60s, with a fallback price
 * and a `source` flag so the UI never has to lie about freshness.
 * Display is in dollars (what markets quote); the ledger settles euros.
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
    private static final Map<String, BigDecimal> FALLBACK = Map.of(
            "btc", new BigDecimal("90000.00"),
            "aapl", new BigDecimal("195.00"));
    private static final Map<String, BigDecimal> FALLBACK_USD = Map.of(
            "btc", new BigDecimal("98000.00"),
            "aapl", new BigDecimal("212.00"));

    private PriceFeed() {}

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
        String enc = Cache.getOrLoad("prices:live2", symbol, 60, () -> {
            try {
                Px f = fetch(symbol);
                return f.price().toPlainString() + '|' + f.usd().toPlainString() + "|live|"
                        + (f.prevClose() == null ? "" : f.prevClose().toPlainString());
            } catch (Exception e) { return null; }
        });
        Px px;
        if (enc != null) {
            px = decode(enc, symbol);
        } else if (hit != null) {          // upstream down: last good price, honestly labeled
            Px last = (Px) hit[0];
            px = new Px(last.price(), last.usd(), "cached", last.prevClose());
        } else {
            px = fallback(symbol);
        }
        cache.put(symbol, new Object[]{px, System.currentTimeMillis()});
        return px;
    }

    /**
     * The last resort · and for most symbols there is not one.
     *
     * The two hardcoded prices exist so the demo still renders when CoinGecko
     * rate-limits us, and they are labeled 'fallback' so the UI can grey them
     * out. There is deliberately no generic fallback: inventing a price for a
     * symbol we have never fetched would be worse than admitting we have
     * none, so an unknown symbol comes back unpriced and stays that way.
     */
    private static Px fallback(String symbol) {
        BigDecimal eur = FALLBACK.get(symbol);
        if (eur == null) return new Px(null, null, "unavailable", null);
        return new Px(eur, FALLBACK_USD.get(symbol), "fallback", null);
    }

    private static Px decode(String enc, String symbol) {
        try {
            String[] p = enc.split("\\|", -1);          // -1: keep a trailing empty prevClose
            BigDecimal prev = p.length > 3 && !p[3].isBlank() ? new BigDecimal(p[3]) : null;
            return new Px(new BigDecimal(p[0]), new BigDecimal(p[1]), p[2], prev);
        } catch (Exception e) {
            return fallback(symbol);
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
        BigDecimal rate = FxClient.usdToEur().rate();
        BigDecimal prevUsd = null;
        try {
            // the payload calls it chartPreviousClose · there is no
            // 'previousClose' key in the range=1d response, verified against
            // a live body rather than assumed
            prevUsd = extract(chart, "\"chartPreviousClose\"\\s*:\\s*([0-9.]+)");
        } catch (Exception ignored) {
            // no prior close in this payload · the day change goes unreported
        }
        // BOTH legs converted with the SAME rate, deliberately. Converting the
        // mark now and the close on some other call's rate would smuggle FX
        // drift into a number the screen labels as the stock's day move.
        return new Px(usd.multiply(rate).setScale(2, RoundingMode.HALF_UP), usd, "live",
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
        BigDecimal rate = FxClient.usdToEur().rate();
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
