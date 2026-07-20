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

    /**
     * A PLATFORM thread, deliberately, and it is the one thread choice in this
     * service that is not obvious.
     *
     * Virtual threads are for work that BLOCKS AND UNMOUNTS: thousands of short
     * request handlers, each parked on a socket, freeing its carrier while it
     * waits. That is what the HTTP server uses them for and it is the right
     * call there.
     *
     * A Kafka consumer loop is the opposite shape. It is one long-lived thread
     * that never finishes, and KafkaConsumer guards its state with synchronized
     * blocks, so when it blocks inside one the virtual thread CANNOT unmount and
     * the carrier is pinned underneath it. This box has 2 vCPUs, so the
     * scheduler starts with 2 carriers, and this JVM runs three of these loops.
     * The scheduler compensates by growing the pool, which is why ForkJoinPool-1
     * was found running 6 threads on a 2-core box: not throughput, apology.
     *
     * The symptom was not a crash. Notifications arrived tens of seconds after
     * the commit that caused them, with consumer lag reading zero the whole
     * time, and two events for different transactions landing 37ms apart after
     * a 22 second gap · the signature of a loop that was not scheduled and then
     * drained everything at once.
     *
     * One platform thread per loop, three in total, costs about a megabyte of
     * stack each and takes them out of the carrier pool entirely.
     */
    public static Thread start(String bootstrapServers) {
        return Thread.ofPlatform().name("notificationsconsumer").daemon().start(() -> {
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
