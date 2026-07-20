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

    private static final String GROUP = "notifications";

    private NotificationsConsumer() {}

    /** How many times an event is re-attempted before it is recorded as stuck. */
    static final int ATTEMPTS = 3;

    /**
     * Handle one record, retrying, and record it as stuck if it will not go.
     *
     * Same shape as Settlement.deliver and ShardApplier.deliver, deliberately.
     * A notification is not money, but the failure mode was identical: catch,
     * print, auto-commit, and the event is gone. handle() is idempotent (the
     * event key is the primary key), so a re-attempt after a partial failure
     * costs nothing · which is what makes retrying safe here.
     */
    static void deliver(String key, String payload) {
        Exception last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                Notifications.handle(key, payload);
                return;
            } catch (Exception e) {
                last = e;
                if (attempt < ATTEMPTS) {
                    try {
                        Thread.sleep(200L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        park(key, payload, last);
    }

    /** The durable admission, in this service's OWN database · dead letters
     *  are state, and database-per-service applies to failures too. If even
     *  THIS fails the process is in no state to hide it, so it goes to
     *  stderr as a last resort rather than silently. */
    static void park(String eventKey, String payload, Exception cause) {
        String key = eventKey != null ? eventKey
                : Json.str(payload, "type") + ":" + Json.str(payload, "txId");
        try (java.sql.Connection c = Notifications.openOwnDb()) {
            DeadLetter.record(c, GROUP, key, "payments", payload,
                    cause == null ? "unknown" : cause.toString(), ATTEMPTS);
        } catch (Exception e) {
            System.err.println("notifications: could not record dead letter for " + key + ": " + e);
        }
        System.err.println("notifications STUCK · " + key + " · " + cause);
    }

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
            p.put("group.id", GROUP);                     // stable: offsets survive restarts
            p.put("auto.offset.reset", "earliest");
            // See SettlementConsumer: auto-commit defaulted to true, so an
            // event whose handler threw was dropped with its offset already
            // past it · the customer's notification silently never happened.
            // Committing only after the batch has been dealt with means a
            // crash mid-batch redelivers rather than skips, and handle() is
            // idempotent, so a redelivery costs one ON CONFLICT no-op.
            p.put("enable.auto.commit", "false");
            p.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            p.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
                consumer.subscribe(List.of("payments"));
                while (!Thread.currentThread().isInterrupted()) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                    for (ConsumerRecord<String, String> r : records) deliver(r.key(), r.value());
                    if (!records.isEmpty()) consumer.commitSync();
                }
            } catch (org.apache.kafka.common.errors.InterruptException ignored) {
            }
        });
    }
}
