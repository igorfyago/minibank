package dev.minibank.ledger;

import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Jedis;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * REDIS · a read-through cache in front of slow or hot reads.
 *
 * Two rules the demo makes physical:
 *   1. Cache only what tolerates staleness · market prices, FX rates and the
 *      routing directory, NEVER a balance the money path is about to lock.
 *   2. A cache must never take the system down with it · if Redis is absent
 *      or unreachable, every call falls straight through to the loader. The
 *      bank is correct without Redis and merely faster with it.
 *
 * Jedis is a driver, not a framework · same category as the Postgres and
 * Kafka clients. Every lookup is counted (hit/miss) into Metrics, so the
 * X-ray shows a live hit rate and Prometheus can graph it.
 */
public final class Cache {

    private static volatile JedisPool pool;   // null = Redis not configured / down

    private Cache() {}

    /** Wire up from REDIS_URL (redis://host:port). Never throws · a bad URL
     *  just leaves the cache in passthrough mode. */
    public static void init(String url) {
        if (url == null || url.isBlank()) return;
        try {
            java.net.URI u = java.net.URI.create(url);
            JedisPoolConfig cfg = new JedisPoolConfig();
            cfg.setMaxTotal(16);
            cfg.setMaxWait(Duration.ofMillis(200));
            JedisPool p = new JedisPool(cfg, u.getHost(),
                    u.getPort() < 0 ? 6379 : u.getPort(), 300);
            try (Jedis j = p.getResource()) { j.ping(); }   // prove it before we trust it
            pool = p;
            System.out.println("cache: Redis connected at " + u.getHost() + ':' + u.getPort());
        } catch (Exception e) {
            pool = null;
            System.out.println("cache: Redis unavailable (" + e.getMessage() + ") · running passthrough");
        }
    }

    public static boolean enabled() { return pool != null; }

    /** Read-through: return the cached value, or load it, store it with a TTL
     *  and return it. Any Redis error degrades to a direct load. */
    public static String getOrLoad(String cacheName, String key, int ttlSeconds, Supplier<String> loader) {
        JedisPool p = pool;
        String full = "mb:" + cacheName + ':' + key;
        // try the read; a Redis error just means "not a hit", never a throw
        if (p != null) {
            try (Jedis j = p.getResource()) {
                String hit = j.get(full);
                if (hit != null) {
                    Metrics.inc("minibank_cache_total", "cache=\"" + cacheName + "\",result=\"hit\"");
                    return hit;
                }
            } catch (Exception ignored) { /* degrade to a load */ }
        }
        // miss: load EXACTLY once (the loader may be the money-path price fetch),
        // then best-effort write it back · a null result is never cached
        Metrics.inc("minibank_cache_total", "cache=\"" + cacheName + "\",result=\"miss\"");
        String val = loader.get();
        if (p != null && val != null) {
            try (Jedis j = p.getResource()) { j.setex(full, ttlSeconds, val); } catch (Exception ignored) {}
        }
        return val;
    }

    /** Invalidate one key · used when the directory pointer flips on relocation. */
    public static void invalidate(String cacheName, String key) {
        JedisPool p = pool;
        if (p == null) return;
        try (Jedis j = p.getResource()) { j.del("mb:" + cacheName + ':' + key); } catch (Exception ignored) {}
    }
}
