package dev.minibank.ledger;

/**
 * The whole bank, one process (each piece is a service waiting to be cut
 * loose · the boundaries are already database-shaped):
 *
 *   HTTP API + router + web app ... :8080  (virtual thread per request)
 *   ledger, SHARDED ............... shard0 :5434, shard1 :5435
 *                                   (a pool per shard; igor lives on 0,
 *                                    coco on 1 · every igor->coco payment
 *                                    is a real cross-shard saga)
 *   outbox relays ................. one virtual thread PER SHARD -> Kafka
 *   shard applier ................. Kafka -> destination shard (arrivals)
 *   notifications consumer ........ Kafka -> its own database
 *
 * Requires: docker compose up -d   (shards :5434/:5435, kafka :9092,
 *                                   postgres :5433 hosts notifications)
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        String kafka = System.getenv().getOrDefault("MINIBANK_KAFKA", "localhost:9092");
        int port = Integer.parseInt(System.getenv().getOrDefault("MINIBANK_PORT", "8080"));
        int poolSize = Integer.parseInt(System.getenv().getOrDefault("MINIBANK_POOL", "10"));

        // Redis · a read-through cache in front of prices and market history.
        // Absent or down? The bank runs identically, just without the cache.
        Cache.init(System.getenv().getOrDefault("REDIS_URL", "redis://localhost:6379"));

        String shard0Url = System.getenv().getOrDefault("MINIBANK_SHARD0_URL", "jdbc:postgresql://localhost:5434/minibank");
        String shard1Url = System.getenv().getOrDefault("MINIBANK_SHARD1_URL", "jdbc:postgresql://localhost:5435/minibank");
        Shards.boot(shard0Url, shard1Url, "minibank", "minibank", poolSize);

        // Flyway migrates each shard database from db/shard/V*.sql BEFORE any
        // seeding · the schema is versioned SQL, recorded in flyway_schema_history.
        Migrate.run(shard0Url, "minibank", "minibank", "classpath:db/shard");
        Migrate.run(shard1Url, "minibank", "minibank", "classpath:db/shard");

        // STAGE 6: the shards become REGIONS · routing by residency
        // (directory lookup), not by arithmetic. igor starts in eu, coco
        // in uk; the Relocate button moves people and the directory
        // remembers across restarts (register is first-write-wins).
        Directory.createOwnDatabase();
        Shards.nameRegions("eu", "uk");
        Directory.register(10, "igor", 0);
        Directory.register(11, "coco", 1);
        Directory.register(12, "oscar", 0);   // eu, same region as igor
        Shards.setResolver(Directory::shardOf);

        Shards.createAndSeed();
        for (long id : new long[]{10, 11, 12}) Products.ensureFor(id);   // the product shelf
        // reconciliation: earlier builds relocated the main account only,
        // stranding product balances on the customer's old region. No-op
        // once every shelf is home.
        int repaired = Relocation.repairShelves();
        if (repaired > 0) System.out.println("shelf repair: " + repaired + " account(s) brought home");
        Notifications.createOwnDatabase();

        for (Shard s : Shards.all()) {
            new OutboxRelay(kafka, s::open).runLoop(500);   // a relay per shard
        }
        ShardApplier.start(kafka);
        Settlement.start(kafka);        // settles the broker service's fills
        NotificationsConsumer.start(kafka);

        // metrics sampler · keeps the Prometheus gauges fresh on their own
        // interval, so a scrape is accurate whether or not anyone is looking
        // at the X-ray. The counters and histogram update on the request path.
        Thread.ofVirtual().name("metrics-sampler").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    for (Shard s : Shards.all()) {
                        String region = Shards.regionName(s.index);
                        Metrics.gauge("minibank_pool_busy", "region=\"" + region + "\"", s.pool().borrowedCount());
                        try (var c = s.open(); var st = c.createStatement();
                             var rs = st.executeQuery("SELECT COUNT(*) FROM outbox WHERE published_at IS NULL")) {
                            rs.next();
                            Metrics.gauge("minibank_outbox_pending", "region=\"" + region + "\"", rs.getLong(1));
                        }
                    }
                    Metrics.gauge("minibank_inflight_eur", "", Shards.inFlight().longValue());
                } catch (Exception ignored) { /* transient · next tick retries */ }
                try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
            }
        });

        // THE FX SERVICE · its own container in production (FX_URL set);
        // the one-command dev run embeds it on :8090
        if (System.getenv("FX_URL") == null) dev.minibank.fx.FxService.start(8090);

        HttpApi.start(port);

        System.out.println("minibank up (sharded): http://localhost:" + port);
        Thread.currentThread().join();   // the virtual threads do the work
    }
}
