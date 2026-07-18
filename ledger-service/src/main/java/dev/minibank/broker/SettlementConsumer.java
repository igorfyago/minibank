package dev.minibank.broker;

import dev.minibank.ledger.Json;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * The far side of the saga: what the bank decided about our fills.
 *
 * The broker publishes to 'orders' and consumes from 'settlements'. One
 * direction each, so neither service can feed itself, and the whole
 * conversation is legible from the topic list alone.
 *
 * Both outcomes are handled here and both are idempotent, because Kafka
 * will deliver each of them more than once eventually:
 *
 *   trade.settled   the money moved · the order is done
 *   trade.rejected  the money refused · the position comes back, and the
 *                   customer is exactly where they started
 */
public final class SettlementConsumer {

    public static final String TOPIC_SETTLEMENTS = "settlements";
    private static final String GROUP = "broker-orders";

    private SettlementConsumer() {}

    public static Thread start(String bootstrapServers, Broker broker) {
        return Thread.startVirtualThread(() -> {
            Properties p = new Properties();
            p.put("bootstrap.servers", bootstrapServers);
            p.put("group.id", GROUP);
            p.put("auto.offset.reset", "earliest");
            p.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            p.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
                consumer.subscribe(List.of(TOPIC_SETTLEMENTS));
                while (!Thread.currentThread().isInterrupted()) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                    for (ConsumerRecord<String, String> r : records) {
                        try {
                            handle(broker, r.value());
                        } catch (Exception e) {
                            System.err.println("broker settlement: " + e.getMessage());
                        }
                    }
                }
            } catch (org.apache.kafka.common.errors.InterruptException ignored) {
            }
        });
    }

    public static void handle(Broker broker, String payload) throws Exception {
        String type = Json.str(payload, "type");
        String fillId = Json.str(payload, "fillId");
        if (fillId == null) return;

        if ("trade.settled".equals(type)) {
            broker.markSettled(UUID.fromString(fillId));
        } else if ("trade.rejected".equals(type)) {
            String reason = Json.str(payload, "reason");
            broker.compensate(UUID.fromString(fillId), reason == null ? "refused" : reason);
        }
    }
}
