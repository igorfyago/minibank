package dev.minibank.ledger;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.UUID;

/**
 * The whole bank, one process (for now — services split into their own
 * processes when the lessons demand it):
 *
 *   HTTP API + web app ........ :8080   (virtual thread per request)
 *   outbox relay .............. virtual thread, polls -> Kafka
 *   notifications consumer .... virtual thread, Kafka -> its own database
 *
 * Requires: docker compose up -d   (postgres :5433, kafka :9092)
 */
public final class Main {

    static final long WORLD = 1, CAFE = 2, IGOR = 10, COCO = 11;

    public static void main(String[] args) throws Exception {
        String kafka = System.getenv().getOrDefault("MINIBANK_KAFKA", "localhost:9092");
        int port = Integer.parseInt(System.getenv().getOrDefault("MINIBANK_PORT", "8080"));

        Db.usePool(Integer.parseInt(System.getenv().getOrDefault("MINIBANK_POOL", "10")));
        Ledger.createTables();
        Notifications.createOwnDatabase();
        seedIfEmpty();

        OutboxRelay relay = new OutboxRelay(kafka);
        relay.runLoop(500);
        NotificationsConsumer.start(kafka);
        HttpApi.start(port);

        System.out.println("minibank up: http://localhost:" + port);
        Thread.currentThread().join();   // the virtual threads do the work
    }

    /** Demo cast, created once: igor and coco funded by the world. */
    private static void seedIfEmpty() throws Exception {
        try (Connection c = Db.open(); var st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM accounts")) {
            rs.next();
            if (rs.getLong(1) > 0) return;
        }
        Ledger.createAccount(WORLD, "world", Ledger.KIND_EXTERNAL);
        Ledger.createAccount(CAFE, "cafe", Ledger.KIND_EXTERNAL);
        Ledger.createAccount(IGOR, "igor", Ledger.KIND_CUSTOMER);
        Ledger.createAccount(COCO, "coco", Ledger.KIND_CUSTOMER);
        Ledger.transfer(UUID.randomUUID(), WORLD, IGOR, new BigDecimal("500.00"));
        Ledger.transfer(UUID.randomUUID(), WORLD, COCO, new BigDecimal("500.00"));
        System.out.println("seeded: igor and coco funded with 500.00 each");
    }
}
