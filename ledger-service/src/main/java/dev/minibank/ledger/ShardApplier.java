package dev.minibank.ledger;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * STAGE 5 · THE SECOND HALF OF EVERY CROSS-SHARD TRANSFER.
 *
 * Consumes the payments topic; on a "transfer.departed" event it routes to
 * the destination customer's shard and applies the arrival there. Kafka
 * delivers at-least-once, the arrival is gated by the txId on the
 * destination shard · so the money lands exactly once no matter how many
 * times the event shows up. This is the outbox/idempotency machinery from
 * stage 2, promoted from "notifications garnish" to MOVING THE MONEY.
 *
 * If the destination account does not exist, the saga cannot complete ·
 * it COMPENSATES: refund on the source shard, gated just as hard. Money
 * is never lost, never duplicated; at worst it is briefly in the pipe.
 */
public final class ShardApplier {

    private ShardApplier() {}

    /** Handle one event. Deterministic and safe to call repeatedly · the
     *  tests feed it the exact payloads the outbox wrote. */
    public static void handle(String payload) throws Exception {
        if (!"transfer.departed".equals(Json.str(payload, "type"))) return;   // not ours

        UUID txId = UUID.fromString(Json.str(payload, "txId"));
        long from = Long.parseLong(Json.num(payload, "from"));
        long to = Long.parseLong(Json.num(payload, "to"));
        BigDecimal amount = new BigDecimal(Json.str(payload, "amount"));

        Ledger.TransferResult r = Shards.forCustomer(to).arrive(txId, to, amount);
        if (r instanceof Ledger.NoSuchAccount) {
            Shards.forCustomer(from).refund(txId, from, amount);
            Metrics.inc("minibank_ledger_events_total", "kind=\"saga_refund\"");
            return;
        }
        // A redelivery lands here as AlreadyProcessed, and it is counted under
        // its own kind rather than as another arrival. Idempotency that is
        // invisible on the dashboard looks exactly like duplicate money, and
        // the whole point of this consumer is that it is neither.
        Metrics.inc("minibank_ledger_events_total",
                r instanceof Ledger.AlreadyProcessed ? "kind=\"saga_redelivered\"" : "kind=\"saga_arrive\"");
    }

    /** Production mode: the consumer loop, on a virtual thread. */
    /** A PLATFORM thread, for the reason spelled out in
     *  NotificationsConsumer: a KafkaConsumer loop pins its carrier and
     *  this box has two. */
    public static Thread start(String bootstrapServers) {
        return Thread.ofPlatform().name("shardapplier").daemon().start(() -> {
            Properties p = new Properties();
            p.put("bootstrap.servers", bootstrapServers);
            p.put("group.id", "shard-applier");           // its own offsets, its own pace
            p.put("auto.offset.reset", "earliest");
            p.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            p.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
                consumer.subscribe(List.of("payments"));
                while (!Thread.currentThread().isInterrupted()) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                    for (ConsumerRecord<String, String> r : records) {
                        try {
                            handle(r.value());
                        } catch (Exception e) {
                            System.err.println("applier: " + e.getMessage());
                        }
                    }
                }
            } catch (org.apache.kafka.common.errors.InterruptException ignored) {
            }
        });
    }
}
