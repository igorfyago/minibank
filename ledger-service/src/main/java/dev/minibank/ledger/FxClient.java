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

    /** The one source string that means NOBODY EVER QUOTED THIS · see {@link Rate#observed}. */
    public static final String FALLBACK_SOURCE = "fx down · fallback";

    public record Rate(BigDecimal rate, String source) {

        /**
         * Whether this rate was ever quoted by anything.
         *
         * "live" and "fx down · last good" are both observations · one is
         * current, one has an old timestamp, and both were true of the
         * market at some moment. STATIC_FALLBACK is not: 0.88 is a constant
         * someone typed, and a number derived from it is a fact about
         * nothing.
         *
         * Callers that merely need the money path to keep moving may use it
         * anyway · that is what the fallback is for. Callers that PUBLISH a
         * figure must not present it as a price, which is what this method
         * exists to let them ask.
         */
        public boolean observed() {
            return !FALLBACK_SOURCE.equals(source);
        }
    }

    private static final String BASE = System.getenv().getOrDefault("FX_URL", "http://localhost:8090");
    private static final BigDecimal STATIC_FALLBACK = new BigDecimal("0.88");
    private static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(300)).build();
    private static volatile Rate lastGood;
    private static volatile Object[] cache;   // [Rate, atMillis]

    private FxClient() {}

    /**
     * USD→EUR, WITHOUT WAITING ON THE FX SERVICE once a rate is known.
     *
     * The deadline below is 500ms and the money path is entitled to spend it,
     * because a payment that must convert has nothing else to do. A SCREEN is
     * a different case: the portfolio endpoint converts every equity mark
     * through this rate, so a cold lookup put its full cost in front of the
     * page · measured at about 70ms of the load, and up to the whole 500ms
     * when the fx-service is unwell.
     *
     * So the same doctrine as PriceFeed.get applies here: a known rate is
     * served instantly whatever its age, and a stale one is refreshed behind
     * the caller rather than in front of them. Only a caller with NO rate at
     * all blocks, and {@link Refresher} exists to make that state brief.
     */
    public static Rate usdToEur() {
        Object[] hit = cache;
        if (hit != null) {
            if (System.currentTimeMillis() - (long) hit[1] < 60_000) return (Rate) hit[0];
            // stale: serve it, refresh behind. The label already tells the
            // truth · a rate we fetched earlier is 'fx down · last good' or
            // whatever the service called it, never silently 'live'.
            //
            // If there is no refresher running to hand that work to, this
            // falls back to the blocking lookup it always did. A stale value
            // plus a refresh that nobody performs is a rate that ages forever,
            // and that is strictly worse than the 500ms this was trying to
            // avoid.
            if (Refresher.FxRefresh.queue()) return (Rate) hit[0];
            return refreshNow();
        }
        return refreshNow();
    }

    /**
     * One lookup, and the cache updated · the blocking path, now called by the
     * refresher on a schedule instead of by a customer on a page load.
     *
     * A failed lookup is aged 50s on the way in, so a dead FX service is
     * retried in ~10s rather than pinned for a minute · the same trick as
     * before, kept because it is the difference between noticing a recovery
     * and waiting out a full window for it.
     */
    static Rate refreshNow() {
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
            Metrics.inc("minibank_fx_lookups_total", "source=\"" + r.source() + "\"");
            return r;
        } catch (Exception e) {
            Metrics.inc("minibank_fx_lookups_total", "source=\"down\"");
            Rate lg = lastGood;
            return lg != null ? new Rate(lg.rate(), "fx down · last good")
                              : new Rate(STATIC_FALLBACK, FALLBACK_SOURCE);
        }
    }
}
