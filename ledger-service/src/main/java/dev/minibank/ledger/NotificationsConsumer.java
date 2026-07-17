package dev.minibank.ledger;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;

import java.time.Duration;
import java.util.List;
import java.util.Properties;

/**
 * The notifications service's consumer loop: reads the payments topic,
 * handles each event idempotently. A stable group id means Kafka remembers
 * our position · restarts resume where we left off, and at-least-once
 * delivery across restarts is exactly why handle() must stay idempotent.
 */
public final class NotificationsConsumer {

    private NotificationsConsumer() {}

    public static Thread start(String bootstrapServers) {
        return Thread.startVirtualThread(() -> {
            Properties p = new Properties();
            p.put("bootstrap.servers", bootstrapServers);
            p.put("group.id", "notifications");           // stable: offsets survive restarts
            p.put("auto.offset.reset", "earliest");
            p.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            p.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
                consumer.subscribe(List.of("payments"));
                while (!Thread.currentThread().isInterrupted()) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                    for (ConsumerRecord<String, String> r : records) {
                        try {
                            Notifications.handle(r.key(), r.value());
                        } catch (Exception e) {
                            System.err.println("notifications: " + e.getMessage());
                        }
                    }
                }
            } catch (org.apache.kafka.common.errors.InterruptException ignored) {
            }
        });
    }
}
