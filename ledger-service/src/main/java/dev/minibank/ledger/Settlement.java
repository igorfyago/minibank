package dev.minibank.ledger;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * SETTLEMENT · where the broker's fill becomes the bank's money.
 *
 * THE PROBLEM, stated plainly. A fill changes two things that live in two
 * databases owned by two services: a position (broker) and a balance
 * (ledger). No transaction spans them. Two-phase commit would block both
 * databases while a coordinator decided, and the coordinator would be a new
 * single point of failure. So this is a SAGA, and it is the same saga the
 * cross-region payment already uses · local transactions glued by events,
 * with a compensation for the unhappy path:
 *
 *   broker    fill + outbox('order.filled')          ONE commit, broker db
 *      |      ... Kafka topic 'orders' ...
 *   ledger    cash leg + asset leg + outbox(result)  ONE commit, shard db
 *      |      ... Kafka topic 'settlements' ...
 *   broker    order settled, or COMPENSATED          ONE commit, broker db
 *
 * Two topics, one direction each. A consumer that produced back onto the
 * topic it consumes would be a loop waiting to happen.
 *
 * THE GATE is the fill id, reused verbatim as the ledger's txId. Kafka is
 * at-least-once, so this event will arrive twice sooner or later; the
 * transactions table has seen that id before and the second delivery
 * changes nothing. No new idempotency mechanism was invented here, because
 * the bank already had the right one.
 *
 * WHY MONEY CAN STILL SAY NO. The broker reserves nothing before routing:
 * the venue fills first and asks about funds afterwards. That is a
 * deliberate simplification and the price of it is exactly this rejection
 * path · the ledger refuses, the broker unwinds the position, and the
 * customer is where they started. A real brokerage removes this whole
 * branch by checking buying power BEFORE routing, which is the same
 * reserve-then-capture shape this bank already implements for card
 * authorizations (Products.authorize / capture / release). Wiring the
 * order path through a hold is the honest next step, and it is a product
 * decision, not a bug fix.
 */
public final class Settlement {

    public static final String TOPIC_ORDERS = "orders";
    public static final String TOPIC_SETTLEMENTS = "settlements";
    private static final String GROUP = "broker-settlement";

    private Settlement() {}

    public static Thread start(String bootstrapServers) {
        return Thread.startVirtualThread(() -> {
            Properties p = new Properties();
            p.put("bootstrap.servers", bootstrapServers);
            p.put("group.id", GROUP);                 // stable: offsets survive restarts
            p.put("auto.offset.reset", "earliest");
            // See SettlementConsumer: auto-commit defaulted to true, so a
            // settlement that threw was dropped with its offset already past
            // it. The order then sits at 'filled' forever, counting as
            // in-flight, and nothing re-attempts the money.
            p.put("enable.auto.commit", "false");
            p.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            p.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
                consumer.subscribe(List.of(TOPIC_ORDERS));
                while (!Thread.currentThread().isInterrupted()) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                    for (ConsumerRecord<String, String> r : records) deliver(r.value());
                    if (!records.isEmpty()) consumer.commitSync();
                }
            } catch (org.apache.kafka.common.errors.InterruptException ignored) {
            }
        });
    }

    /** How many times a settlement is re-attempted before it is recorded as stuck. */
    static final int ATTEMPTS = 3;

    /**
     * Settle one record, retrying, and record it as stuck if it will not go.
     *
     * settleFill is gated on the fill id, so a re-attempt after a partial
     * failure either finds the claim taken (AlreadyProcessed, nothing moves)
     * or starts clean · which is what makes retrying safe here rather than a
     * second way to double-charge someone.
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

    static void park(String payload, Exception cause) {
        String key = Json.str(payload, "type") + ":" + Json.str(payload, "fillId");
        try {
            long customerId = Long.parseLong(Json.num(payload, "customer"));
            try (Connection c = Shards.forCustomer(customerId).open()) {
                DeadLetter.record(c, GROUP, key, TOPIC_ORDERS, payload,
                        cause == null ? "unknown" : cause.toString(), ATTEMPTS);
            }
        } catch (Exception e) {
            System.err.println("settlement: could not record dead letter for " + key + ": " + e);
        }
        System.err.println("settlement STUCK · " + key + " · " + cause);
    }

    /**
     * Settle one fill. Safe to call with the same event any number of times.
     *
     * Note what this does NOT do: recompute anything. The venue decided the
     * quantity and the price; the ledger's job is to move exactly that, not
     * to have an opinion about it. Recomputing units from cash divided by
     * price would drift by a rounding step and the books would be wrong in
     * the eighth decimal, which is the kind of wrong that takes a week to
     * find.
     */
    public static void handle(String payload) throws SQLException {
        if (!"order.filled".equals(Json.str(payload, "type"))) return;

        UUID fillId = UUID.fromString(Json.str(payload, "fillId"));
        long customerId = Long.parseLong(Json.num(payload, "customer"));
        String symbol = Json.str(payload, "symbol");
        boolean buy = "buy".equals(Json.str(payload, "side"));
        BigDecimal units = new BigDecimal(Json.str(payload, "qty"));
        BigDecimal cash = new BigDecimal(Json.str(payload, "cash"));
        String asset = symbol.toLowerCase();

        // THE LINE THAT USED TO BE THE MIS-CREDIT VECTOR. This method hands
        // the ledger whatever symbol the venue filled, and it never validated
        // it · the HTTP trade path checked, the Kafka settlement path did
        // not, and the ledger's ternary turned every unvalidated symbol into
        // the customer's APPLE account. The registry has no else-branch now,
        // so an unlisted symbol raises. Catch it HERE rather than letting it
        // kill the consumer thread: an instrument the broker can route and
        // the ledger cannot settle is a refusal, which the saga already knows
        // how to unwind, and a redelivery is a no-op because the refusal is
        // gated by a deterministic id.
        Ledger.TransferResult result;
        try {
            result = Products.settleFill(fillId, customerId, asset, buy, units, cash);
        } catch (AssetRegistry.UnknownAsset e) {
            System.err.println("settlement refused: " + e.getMessage());
            Products.recordSettlementRefusal(fillId, customerId, symbol, buy, units,
                    "instrument not listed in the ledger");
            return;
        }

        if (result instanceof Ledger.InsufficientFunds) {
            // the money side said no · tell the broker so it can unwind, and
            // record the refusal in the ledger so a redelivery is a no-op
            Products.recordSettlementRefusal(fillId, customerId, symbol, buy, units,
                    buy ? "insufficient funds" : "insufficient holdings");
        }
        // Ok and AlreadyProcessed both mean the money is where it should be.
        // Ok wrote the settled event inside its own commit; AlreadyProcessed
        // means an earlier delivery already did.
    }
}
