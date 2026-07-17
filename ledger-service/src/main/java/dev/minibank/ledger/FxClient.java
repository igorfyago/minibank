package dev.minibank.ledger;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The bank's view of the FX service · one HTTP call with a HARD deadline
 * and a fallback. FX slow or down? The money path waits at most ~500ms,
 * then uses the last good rate, honestly labeled. A currency lookup must
 * never stall a payment · this deadline + fallback pair is the
 * circuit-breaker pattern at demo scale.
 */
public final class FxClient {

    public record Rate(BigDecimal rate, String source) {}

    private static final String BASE = System.getenv().getOrDefault("FX_URL", "http://localhost:8090");
    private static final BigDecimal STATIC_FALLBACK = new BigDecimal("0.88");
    private static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(300)).build();
    private static volatile Rate lastGood;
    private static volatile Object[] cache;   // [Rate, atMillis]

    private FxClient() {}

    /** USD→EUR via the FX service, cached 60s in-process.
     *  A failed lookup is cached only ~10s, so a dead FX service is
     *  retried soon without being hammered. */
    public static Rate usdToEur() {
        Object[] hit = cache;
        if (hit != null && System.currentTimeMillis() - (long) hit[1] < 60_000) return (Rate) hit[0];
        Rate r = from(BASE);
        long at = r.source().startsWith("fx down") ? System.currentTimeMillis() - 50_000
                                                   : System.currentTimeMillis();
        cache = new Object[]{r, at};
        return r;
    }

    /** One call, one deadline, no cache · the mechanism the lesson tests. */
    static Rate from(String base) {
        try {
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(base + "/rate"))
                    .timeout(Duration.ofMillis(500)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) throw new IllegalStateException("HTTP " + resp.statusCode());
            Matcher m = Pattern.compile("\"rate\"\\s*:\\s*\"([0-9.]+)\"").matcher(resp.body());
            Matcher s = Pattern.compile("\"source\"\\s*:\\s*\"([^\"]+)\"").matcher(resp.body());
            if (!m.find()) throw new IllegalStateException("no rate");
            Rate r = new Rate(new BigDecimal(m.group(1)), s.find() ? s.group(1) : "live");
            lastGood = r;
            return r;
        } catch (Exception e) {
            Rate lg = lastGood;
            return lg != null ? new Rate(lg.rate(), "fx down · last good")
                              : new Rate(STATIC_FALLBACK, "fx down · fallback");
        }
    }
}
