package dev.minibank.ledger;

/**
 * The whole bank, one process (each piece is a service waiting to be cut
 * loose — the boundaries are already database-shaped):
 *
 *   HTTP API + router + web app ... :8080  (virtual thread per request)
 *   ledger, SHARDED ............... shard0 :5434, shard1 :5435
 *                                   (a pool per shard; igor lives on 0,
 *                                    coco on 1 — every igor->coco payment
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

        Shards.boot(
                System.getenv().getOrDefault("MINIBANK_SHARD0_URL", "jdbc:postgresql://localhost:5434/minibank"),
                System.getenv().getOrDefault("MINIBANK_SHARD1_URL", "jdbc:postgresql://localhost:5435/minibank"),
                "minibank", "minibank", poolSize);

        // STAGE 6: the shards become REGIONS — routing by residency
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
        Notifications.createOwnDatabase();

        for (Shard s : Shards.all()) {
            new OutboxRelay(kafka, s::open).runLoop(500);   // a relay per shard
        }
        ShardApplier.start(kafka);
        NotificationsConsumer.start(kafka);
        HttpApi.start(port);

        System.out.println("minibank up (sharded): http://localhost:" + port);
        Thread.currentThread().join();   // the virtual threads do the work
    }
}
