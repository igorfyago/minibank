package dev.minibank.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live prices, honestly labeled. BTC/EUR from CoinGecko; AAPL from Yahoo
 * (USD) converted at the live EURUSD rate from frankfurter.app — all
 * keyless public endpoints, cached for 60s, with a fallback price and a
 * `source` flag so the UI never has to lie about freshness.
 *
 * The price is captured AT EXECUTION and written into the trade's event —
 * the ledger stores the units and the euros; the ratio IS the price paid.
 */
public final class PriceFeed {

    public record Px(BigDecimal price, String source) {}

    private static final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(3)).build();
    private static final Map<String, Object[]> cache = new ConcurrentHashMap<>(); // sym -> [Px, fetchedAtMillis]
    private static final Map<String, BigDecimal> FALLBACK = Map.of(
            "btc", new BigDecimal("90000.00"),
            "aapl", new BigDecimal("195.00"));

    private PriceFeed() {}

    public static Px get(String symbol) {
        Object[] hit = cache.get(symbol);
        if (hit != null && System.currentTimeMillis() - (long) hit[1] < 60_000) return (Px) hit[0];
        Px px;
        try {
            px = new Px(fetch(symbol), "live");
        } catch (Exception e) {
            px = hit != null ? new Px(((Px) hit[0]).price(), "cached")
                             : new Px(FALLBACK.get(symbol), "fallback");
        }
        cache.put(symbol, new Object[]{px, System.currentTimeMillis()});
        return px;
    }

    private static BigDecimal fetch(String symbol) throws Exception {
        if ("btc".equals(symbol)) {
            String body = getBody("https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=eur");
            return extract(body, "\"eur\"\\s*:\\s*([0-9.]+)");
        }
        // AAPL: Yahoo quotes USD; convert with the live EURUSD rate
        String chart = getBody("https://query1.finance.yahoo.com/v8/finance/chart/AAPL?range=1d&interval=1d");
        BigDecimal usd = extract(chart, "\"regularMarketPrice\"\\s*:\\s*([0-9.]+)");
        String fx = getBody("https://api.frankfurter.app/latest?from=USD&to=EUR");
        BigDecimal rate = extract(fx, "\"EUR\"\\s*:\\s*([0-9.]+)");
        return usd.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    private static final Map<String, Object[]> histCache = new ConcurrentHashMap<>();

    /** ~30 days of real prices as [[ms,eur],...] JSON — cached 10 min,
     *  thinned to ≤120 points, empty array when the feed is down. */
    public static String historyJson(String symbol) {
        Object[] hit = histCache.get(symbol);
        if (hit != null && System.currentTimeMillis() - (long) hit[1] < 600_000) return (String) hit[0];
        String out;
        try {
            out = "btc".equals(symbol) ? btcHistory() : aaplHistory();
        } catch (Exception e) {
            System.err.println("pricefeed history " + symbol + ": " + e);
            out = hit != null ? (String) hit[0] : "[]";
        }
        histCache.put(symbol, new Object[]{out, System.currentTimeMillis()});
        return out;
    }

    private static String btcHistory() throws Exception {
        String body = getBody("https://api.coingecko.com/api/v3/coins/bitcoin/market_chart?vs_currency=eur&days=30&interval=daily");
        // the response carries prices, market_caps AND total_volumes —
        // scan ONLY the prices array or the chart plots market caps
        int i = body.indexOf("\"prices\"");
        int j = body.indexOf("]]", i);
        if (i < 0 || j < 0) throw new IllegalStateException("no prices array");
        body = body.substring(i, j + 2);
        Matcher m = Pattern.compile("\\[(\\d+)(?:\\.\\d+)?,([0-9.]+)\\]").matcher(body);
        return thin(m);
    }

    private static String aaplHistory() throws Exception {
        String chart = getBody("https://query1.finance.yahoo.com/v8/finance/chart/AAPL?range=1mo&interval=1d");
        BigDecimal rate = extract(getBody("https://api.frankfurter.app/latest?from=USD&to=EUR"), "\"EUR\"\\s*:\\s*([0-9.]+)");
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
