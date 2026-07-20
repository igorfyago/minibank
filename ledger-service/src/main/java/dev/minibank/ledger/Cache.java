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

    /**
     * READ ONLY · no loader, no write, and a miss is simply null.
     *
     * Separated from getOrLoad because "is there a shared value" and "may I
     * call the upstream" are two different questions, and answering them with
     * one method conflated them in a way that lost real prices. A caller that
     * is backing off an upstream must still be allowed to read what the fleet
     * already stored; see PriceFeed.loadOrFetch for the failure that made the
     * distinction necessary.
     */
    public static String get(String cacheName, String key) {
        JedisPool p = pool;
        if (p == null) return null;
        try (Jedis j = p.getResource()) {
            String hit = j.get("mb:" + cacheName + ':' + key);
            Metrics.inc("minibank_cache_total",
                    "cache=\"" + cacheName + "\",result=\"" + (hit != null ? "hit" : "miss") + "\"");
            return hit;
        } catch (Exception e) {
            return null;
        }
    }

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

    /**
     * WRITE WITHOUT READING · what a refresh is, as opposed to a load.
     *
     * getOrLoad cannot express this: it is read-through by definition, so it
     * would hand back the very value the refresher exists to replace. With a
     * long stale TTL that is not a small inefficiency, it is a fleet that
     * serves one increasingly ancient price forever and reports a perfect hit
     * rate while doing it.
     */
    public static void put(String cacheName, String key, int ttlSeconds, String value) {
        JedisPool p = pool;
        if (p == null || value == null) return;
        try (Jedis j = p.getResource()) { j.setex("mb:" + cacheName + ':' + key, ttlSeconds, value); }
        catch (Exception ignored) { /* a cache that cannot be written is still not an outage */ }
    }

    /**
     * TAKE A SHORT LEASE, or find that somebody else holds it · SET NX EX.
     *
     * The refresher's answer to "three pods, one upstream". Whoever wins the
     * key does the fetch and publishes it for everyone; the others skip the
     * cycle and read what lands. It is deliberately NOT a lock: nothing is
     * released, nothing is waited on, and a holder that dies mid-refresh
     * costs one skipped interval rather than a stuck fleet. The failure mode
     * of a lease that nobody can take is that every pod refreshes its own,
     * which is precisely the behaviour without Redis · so this degrades to
     * correct-but-noisier rather than to broken.
     */
    public static boolean lease(String cacheName, String key, int ttlSeconds) {
        JedisPool p = pool;
        if (p == null) return true;      // no Redis, no coordination · everyone refreshes their own
        try (Jedis j = p.getResource()) {
            return "OK".equals(j.set("mb:lease:" + cacheName + ':' + key, "1",
                    redis.clients.jedis.params.SetParams.setParams().nx().ex(ttlSeconds)));
        } catch (Exception e) {
            return true;                 // Redis unreachable · fall back to refreshing locally
        }
    }

    /**
     * Does this key exist · asked of a marker that deliberately holds no value.
     *
     * PriceFeed records the FACT that a symbol could not be priced, and that
     * fact is worth sharing across pods and across restarts for the same
     * reason it is worth keeping in process: an unpriceable ticker otherwise
     * costs a full upstream timeout on the first request after every deploy.
     * The marker holds no number by construction · see the negative-cache
     * docstring in PriceFeed · so there is nothing here that could ever be
     * mistaken for a price.
     */
    public static boolean has(String cacheName, String key) {
        JedisPool p = pool;
        if (p == null) return false;
        try (Jedis j = p.getResource()) { return j.exists("mb:" + cacheName + ':' + key); }
        catch (Exception e) { return false; }
    }

    /** Invalidate one key · used when the directory pointer flips on relocation. */
    public static void invalidate(String cacheName, String key) {
        JedisPool p = pool;
        if (p == null) return;
        try (Jedis j = p.getResource()) { j.del("mb:" + cacheName + ':' + key); } catch (Exception ignored) {}
    }
}
