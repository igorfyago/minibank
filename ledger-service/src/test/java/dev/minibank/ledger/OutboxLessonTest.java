package dev.minibank.ledger;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STAGE 2 — EVENTS WITHOUT LIES: THE OUTBOX, THE RELAY, THE IDEMPOTENT CONSUMER.
 *
 *   lesson 1  the event commits WITH the money — same transaction, same fate
 *   lesson 2  relay down? nothing is lost — the echo is late, never absent
 *   lesson 3  end-to-end: outbox -> relay -> Kafka -> consumer -> notification
 *   lesson 4  the same event delivered twice -> exactly one notification
 *
 * Requires: docker compose up -d   (postgres 5433 + kafka 9092)
 */
class OutboxLessonTest {

    static final long WORLD = 1, IGOR = 10, COCO = 11;
    static final BigDecimal EUR_100 = new BigDecimal("100.00");
    static final BigDecimal EUR_30 = new BigDecimal("30.00");
    static final String KAFKA = "localhost:9092";

    @BeforeAll
    static void setup() throws Exception {
        Ledger.createTables();
        Outbox.createTable();
        Notifications.createOwnDatabase();
    }

    @BeforeEach
    void freshBank() throws Exception {
        try (Connection c = Db.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE outbox, entries, transactions, accounts CASCADE");
        }
        Ledger.createAccount(WORLD, "world", Ledger.KIND_EXTERNAL);
        Ledger.createAccount(IGOR, "igor", Ledger.KIND_CUSTOMER);
        Ledger.createAccount(COCO, "coco", Ledger.KIND_CUSTOMER);
        Ledger.transfer(UUID.randomUUID(), WORLD, IGOR, EUR_100);
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 1: the event is committed in the same transaction as the money")
    void lesson1_eventCommitsWithTheMoney() throws Exception {
        int before = Outbox.pollUnpublished(1000).size();

        UUID txId = UUID.randomUUID();
        Ledger.transfer(txId, IGOR, COCO, EUR_30);

        List<Outbox.Event> pending = Outbox.pollUnpublished(1000);
        assertEquals(before + 1, pending.size(), "exactly one new event");
        Outbox.Event event = pending.get(pending.size() - 1);
        assertEquals(txId.toString(), event.key());
        assertTrue(event.payload().contains("payment.completed"));

        // and the counter-proof: a FAILED transfer leaves NO event —
        // the echo cannot exist for money that never moved.
        Ledger.transfer(UUID.randomUUID(), IGOR, COCO, new BigDecimal("999.00"));
        assertEquals(before + 1, Outbox.pollUnpublished(1000).size(), "rejected transfer, no event");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 2: relay down — the echo is late, never lost")
    void lesson2_relayDownNothingLost() throws Exception {
        // The relay is simply not running. Business continues.
        Ledger.transfer(UUID.randomUUID(), IGOR, COCO, EUR_30);
        Ledger.transfer(UUID.randomUUID(), IGOR, COCO, EUR_30);

        // The events sit safely in the outbox, durable, ordered, waiting.
        assertTrue(Outbox.pollUnpublished(1000).size() >= 2,
                "events wait in the outbox until a relay picks them up");
        // (lesson 3 will be that relay. This IS eventual consistency:
        //  the money is strict now, the echoes arrive when they arrive.)
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 3: outbox -> relay -> Kafka -> consumer -> notification, end to end")
    void lesson3_endToEnd() throws Exception {
        UUID txId = UUID.randomUUID();
        Ledger.transfer(txId, IGOR, COCO, EUR_30);

        // relay publishes everything pending (seed transfer + ours)
        try (OutboxRelay relay = new OutboxRelay(KAFKA)) {
            int published = relay.publishPending();
            assertTrue(published >= 1, "relay published the pending events");
        }
        assertEquals(0, Outbox.pollUnpublished(1000).size(), "outbox fully drained");

        // a real consumer reads the topic from the beginning and handles our event
        ConsumerRecord<String, String> record = consumeUntilKey("payments", txId.toString());
        assertNotNull(record, "the event arrived through the broker");
        Notifications.handle(record.key(), record.value());

        assertEquals(1, countNotificationsFor(txId), "coco got her notification");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 4: the same event delivered twice — exactly one notification")
    void lesson4_idempotentConsumer() throws Exception {
        UUID txId = UUID.randomUUID();
        Ledger.transfer(txId, IGOR, COCO, EUR_30);
        try (OutboxRelay relay = new OutboxRelay(KAFKA)) {
            relay.publishPending();
        }
        ConsumerRecord<String, String> record = consumeUntilKey("payments", txId.toString());
        assertNotNull(record);

        // at-least-once means THIS can happen. The consumer must not care.
        Notifications.handle(record.key(), record.value());
        Notifications.handle(record.key(), record.value());   // duplicate delivery
        Notifications.handle(record.key(), record.value());   // and again

        assertEquals(1, countNotificationsFor(txId),
                "idempotent consumer: duplicates vanish on the primary key");
    }

    // ------------------------------------------------------------------
    private static ConsumerRecord<String, String> consumeUntilKey(String topic, String key) {
        Properties p = new Properties();
        p.put("bootstrap.servers", KAFKA);
        p.put("group.id", "test-" + UUID.randomUUID());   // fresh group: read from the beginning
        p.put("auto.offset.reset", "earliest");
        p.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        p.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> r : records) {
                    if (key.equals(r.key())) return r;
                }
            }
        }
        return null;
    }

    private static int countNotificationsFor(UUID txId) throws Exception {
        // count via the service's own API surface: how many notifications exist
        // for this key. (Notifications owns its DB; we ask it, not the tables.)
        // For the lesson we just re-handle idempotently and inspect the count
        // delta — simplest honest probe: query through a tiny helper.
        return NotificationsProbe.countByKey(txId.toString());
    }
}
