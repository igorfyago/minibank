package dev.minibank.ledger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * PROMETHEUS METRICS · hand-rolled, no client library.
 *
 * The exposition format is just text, so we emit it ourselves · a counter
 * is a monotonic number, a gauge is a number that moves, a histogram is a
 * set of cumulative buckets plus _sum and _count. That is the whole format
 * Prometheus scrapes and Grafana graphs. Keeping it dependency-free is the
 * same doctrine as the rest of the bank: understand the wire, don't import
 * the magic.
 *
 * Everything here is in-memory and lock-free (LongAdder / AtomicLong), read
 * once per scrape at GET /metrics.
 */
public final class Metrics {

    private Metrics() {}

    // labelset -> counter. The key is the fully-rendered label string.
    private static final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> gauges = new ConcurrentHashMap<>();

    // one request-latency histogram, seconds, standard-ish buckets
    private static final double[] BUCKETS =
            {0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5};
    private static final LongAdder[] bucketCounts = newAdders(BUCKETS.length + 1); // +Inf
    private static final LongAdder httpSumNanos = new LongAdder();   // exact, not ms-rounded
    private static final LongAdder httpCount = new LongAdder();

    private static LongAdder[] newAdders(int n) {
        LongAdder[] a = new LongAdder[n];
        for (int i = 0; i < n; i++) a[i] = new LongAdder();
        return a;
    }

    /** name{labels} -> +1. Pass an already-rendered label body or "". */
    public static void inc(String name, String labels) {
        counters.computeIfAbsent(key(name, labels), k -> new LongAdder()).increment();
    }
    public static void inc(String name) { inc(name, ""); }

    public static void gauge(String name, String labels, long value) {
        gauges.computeIfAbsent(key(name, labels), k -> new AtomicLong()).set(value);
    }

    /** Record one HTTP request's latency into the histogram. The sum is
     *  accumulated in nanoseconds (same precision the buckets classify on),
     *  so sub-millisecond requests are not rounded away. */
    public static void observeHttp(double seconds) {
        httpSumNanos.add(Math.round(seconds * 1e9));
        httpCount.increment();
        for (int i = 0; i < BUCKETS.length; i++) {
            if (seconds <= BUCKETS[i]) { bucketCounts[i].increment(); return; }
        }
        bucketCounts[BUCKETS.length].increment(); // +Inf
    }

    private static String key(String name, String labels) {
        return labels == null || labels.isEmpty() ? name : name + "{" + labels + "}";
    }

    /** The full /metrics body in Prometheus text exposition format. */
    public static String scrape() {
        StringBuilder b = new StringBuilder(4096);

        b.append("# HELP minibank_http_requests_total HTTP requests, by route class and status.\n");
        b.append("# TYPE minibank_http_requests_total counter\n");
        emit(b, "minibank_http_requests_total");

        b.append("# HELP minibank_ledger_events_total Money-path events (transfers, trades, saga legs, publishes).\n");
        b.append("# TYPE minibank_ledger_events_total counter\n");
        emit(b, "minibank_ledger_events_total");

        b.append("# HELP minibank_cache_total Redis cache lookups, by cache and result.\n");
        b.append("# TYPE minibank_cache_total counter\n");
        emit(b, "minibank_cache_total");

        b.append("# HELP minibank_fx_lookups_total FX upstream fetches (cache misses), by outcome (live or down).\n");
        b.append("# TYPE minibank_fx_lookups_total counter\n");
        emit(b, "minibank_fx_lookups_total");

        // each gauge family must be contiguous: HELP + TYPE immediately
        // followed by ITS OWN samples, or a strict parser drops the type
        b.append("# HELP minibank_inflight_eur Money in flight across regions, right now.\n");
        b.append("# TYPE minibank_inflight_eur gauge\n");
        emitGauge(b, "minibank_inflight_eur");
        b.append("# HELP minibank_pool_busy Busy pooled connections, by region.\n");
        b.append("# TYPE minibank_pool_busy gauge\n");
        emitGauge(b, "minibank_pool_busy");
        b.append("# HELP minibank_outbox_pending Unpublished outbox rows, by region.\n");
        b.append("# TYPE minibank_outbox_pending gauge\n");
        emitGauge(b, "minibank_outbox_pending");

        b.append("# HELP minibank_http_request_duration_seconds Request latency.\n");
        b.append("# TYPE minibank_http_request_duration_seconds histogram\n");
        long cumulative = 0;
        for (int i = 0; i < BUCKETS.length; i++) {
            cumulative += bucketCounts[i].sum();
            b.append("minibank_http_request_duration_seconds_bucket{le=\"")
             .append(BUCKETS[i]).append("\"} ").append(cumulative).append('\n');
        }
        cumulative += bucketCounts[BUCKETS.length].sum();
        b.append("minibank_http_request_duration_seconds_bucket{le=\"+Inf\"} ").append(cumulative).append('\n');
        b.append("minibank_http_request_duration_seconds_sum ")
         .append(httpSumNanos.sum() / 1e9).append('\n');
        b.append("minibank_http_request_duration_seconds_count ").append(httpCount.sum()).append('\n');

        return b.toString();
    }

    private static void emit(StringBuilder b, String name) {
        for (var e : counters.entrySet()) {
            String k = e.getKey();
            if (k.equals(name) || k.startsWith(name + "{")) b.append(k).append(' ').append(e.getValue().sum()).append('\n');
        }
    }

    private static void emitGauge(StringBuilder b, String name) {
        for (var e : gauges.entrySet()) {
            String k = e.getKey();
            if (k.equals(name) || k.startsWith(name + "{")) b.append(k).append(' ').append(e.getValue().get()).append('\n');
        }
    }

    /** A compact snapshot for the X-ray UI (not the Prometheus body). */
    public static String uiJson() {
        long hits = sum("minibank_cache_total", "result=\"hit\"");
        long misses = sum("minibank_cache_total", "result=\"miss\"");
        long total = hits + misses;
        long httpN = httpCount.sum();
        double avgMs = httpN == 0 ? 0 : (httpSumNanos.sum() / 1e6) / httpN;
        return "{\"cacheHits\":" + hits + ",\"cacheMisses\":" + misses +
               ",\"cacheHitRate\":" + (total == 0 ? 0 : Math.round(hits * 100.0 / total)) +
               ",\"httpRequests\":" + httpN +
               ",\"httpAvgMs\":" + Math.round(avgMs * 10) / 10.0 + "}";
    }

    private static long sum(String name, String labelContains) {
        long s = 0;
        for (var e : counters.entrySet())
            if (e.getKey().startsWith(name + "{") && e.getKey().contains(labelContains)) s += e.getValue().sum();
        return s;
    }
}
