package dev.minibank.ledger;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;

import java.math.BigDecimal;
import java.sql.Connection;
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

    public static final String TOPIC_PAYMENTS = "payments";
    private static final String GROUP = "shard-applier";

    private ShardApplier() {}

    /** How many times an arrival is re-attempted before it is recorded as stuck. */
    static final int ATTEMPTS = 3;

    /**
     * Apply one record, retrying, and record it as stuck if it will not go.
     *
     * Same shape as Settlement.deliver, deliberately: this consumer MOVES THE
     * MONEY, so the doctrine that protects the broker's settlements protects
     * it too. arrive() and refund() are both gated on ids claimed inside their
     * own effect transaction, so a re-attempt after a partial failure either
     * finds the claim taken (AlreadyProcessed, nothing moves) or starts clean
     * · which is what makes retrying safe here rather than a second way to
     * pay someone twice.
     */
    static void deliver(String payload) {
        Exception last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                handle(payload);
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

    /** The durable admission, on the DESTINATION customer's shard · the shard
     *  whose arrival would not go through. If even THIS fails the process is
     *  in no state to hide it, so it goes to stderr as a last resort rather
     *  than silently. */
    static void park(String payload, Exception cause) {
        String key = Json.str(payload, "type") + ":" + Json.str(payload, "txId");
        try {
            long to = Long.parseLong(Json.num(payload, "to"));
            try (Connection c = Shards.forCustomer(to).open()) {
                DeadLetter.record(c, GROUP, key, TOPIC_PAYMENTS, payload,
                        cause == null ? "unknown" : cause.toString(), ATTEMPTS);
            }
        } catch (Exception e) {
            System.err.println("applier: could not record dead letter for " + key + ": " + e);
        }
        System.err.println("applier STUCK · " + key + " · " + cause);
    }

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
            p.put("group.id", GROUP);                     // its own offsets, its own pace
            p.put("auto.offset.reset", "earliest");
            // See SettlementConsumer: auto-commit defaulted to true, so an
            // arrival that threw was dropped with its offset already past it.
            // For THIS consumer that is not a stuck order · it is money that
            // departed one region and will never land in the other, sitting
            // in IN_TRANSIT with nothing left to move it. The arrival is
            // idempotent (arrive claims the txId inside its own transaction),
            // which is exactly why NOT committing on failure is correct: a
            // redelivery of work already done settles into the gate.
            p.put("enable.auto.commit", "false");
            p.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            p.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
                consumer.subscribe(List.of(TOPIC_PAYMENTS));
                while (!Thread.currentThread().isInterrupted()) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                    for (ConsumerRecord<String, String> r : records) deliver(r.value());
                    if (!records.isEmpty()) consumer.commitSync();
                }
            } catch (org.apache.kafka.common.errors.InterruptException ignored) {
            }
        });
    }
}
