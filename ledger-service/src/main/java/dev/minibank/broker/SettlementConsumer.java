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

    /** How many times a step is re-attempted in-process before it is recorded
     *  as stuck. Enough to ride out a shard blip, few enough that a genuinely
     *  impossible compensation is admitted rather than retried forever. */
    static final int ATTEMPTS = 3;

    private SettlementConsumer() {}

    public static Thread start(String bootstrapServers, Broker broker) {
        return Thread.startVirtualThread(() -> {
            Properties p = new Properties();
            p.put("bootstrap.servers", bootstrapServers);
            p.put("group.id", GROUP);
            p.put("auto.offset.reset", "earliest");
            // NOT auto-commit, and this is the whole point. The default is
            // true, so the offset advanced on the next poll whether or not
            // the step had gone through · a compensation that threw was
            // caught, printed and forgotten, with the offset already past it.
            // Committing only after a batch has been dealt with means a crash
            // mid-batch redelivers rather than skips, which the handlers are
            // idempotent enough to survive.
            p.put("enable.auto.commit", "false");
            p.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            p.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
                consumer.subscribe(List.of(TOPIC_SETTLEMENTS));
                while (!Thread.currentThread().isInterrupted()) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                    for (ConsumerRecord<String, String> r : records) deliver(broker, r.value());
                    if (!records.isEmpty()) consumer.commitSync();
                }
            } catch (org.apache.kafka.common.errors.InterruptException ignored) {
            }
        });
    }

    /**
     * Handle one record, retrying, and record it as stuck if it will not go.
     *
     * A failure here is not a log line. The saga promises every fill ends in
     * money or in a compensation, and a step that silently vanished is the one
     * outcome that promise does not cover · so what would not complete lands
     * in the broker's own dead-letter table, where the audit can see it and an
     * operator can re-drive it.
     */
    static void deliver(Broker broker, String payload) {
        Exception last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                handle(broker, payload);
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
        park(payload, last);
    }

    /** The durable admission. If even THIS fails the process is in no state to
     *  hide it, so it goes to stderr as a last resort rather than silently. */
    static void park(String payload, Exception cause) {
        String key = Json.str(payload, "type") + ":" + Json.str(payload, "fillId");
        try (java.sql.Connection c = BrokerDb.open()) {
            dev.minibank.ledger.DeadLetter.record(c, GROUP, key, TOPIC_SETTLEMENTS, payload,
                    cause == null ? "unknown" : cause.toString(), ATTEMPTS);
        } catch (Exception e) {
            System.err.println("broker settlement: could not record dead letter for " + key + ": " + e);
        }
        System.err.println("broker settlement STUCK · " + key + " · " + cause);
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
