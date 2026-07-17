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

    private static String getBody(String url) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(4))
                        .header("User-Agent", "minibank-demo").GET().build(),
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
