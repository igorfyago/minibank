package dev.minibank.ledger;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.sql.SQLException;
import java.util.List;
import java.util.Properties;

/**
 * The relay: moves outbox rows to Kafka. Deliberately boring · poll, send,
 * mark, repeat. Boring is the point: all the cleverness already happened
 * when the event was committed atomically with the money.
 *
 * publishPending() is one deterministic pass (tests call it directly);
 * runLoop() wraps it for production, on a virtual thread · Java 21's cheap
 * threads make "a thread that mostly sleeps" cost almost nothing.
 */
public final class OutboxRelay implements AutoCloseable {

    private final Producer<String, String> producer;
    private final ConnectionSource db;

    public OutboxRelay(String bootstrapServers) {
        this(bootstrapServers, Db::open);
    }

    /** Stage 5: every shard has its own outbox, so every shard gets its own
     *  relay · same code, pointed at a different database. */
    public OutboxRelay(String bootstrapServers, ConnectionSource db) {
        this.db = db;
        Properties p = new Properties();
        p.put("bootstrap.servers", bootstrapServers);
        p.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        p.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        p.put("acks", "all");               // the broker must confirm durable receipt
        p.put("enable.idempotence", "true"); // producer-side retry dedup (broker level)
        this.producer = new KafkaProducer<>(p);
    }

    /** One pass: publish every unpublished event, oldest first. Returns count. */
    public int publishPending() throws SQLException {
        try (java.sql.Connection c = db.open()) {
            List<Outbox.Event> events = Outbox.pollUnpublishedOn(c, 100);
            for (Outbox.Event e : events) {
                try {
                    // send synchronously: only after the broker acks do we mark.
                    producer.send(new ProducerRecord<>(e.topic(), e.key(), e.payload())).get();
                } catch (Exception ex) {
                    throw new RuntimeException("publish failed for outbox id " + e.id(), ex);
                }
                Outbox.markPublishedOn(c, e.id());   // crash before this line -> resent later. At-least-once.
            }
            return events.size();
        }
    }

    /** Production mode: forever, on a virtual thread. */
    public Thread runLoop(long pollMillis) {
        return Thread.startVirtualThread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    if (publishPending() == 0) Thread.sleep(pollMillis);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    // transient failure: log and keep trying · the outbox holds the truth
                    System.err.println("relay: " + e.getMessage());
                    try { Thread.sleep(pollMillis); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        });
    }

    @Override
    public void close() {
        producer.close();
    }
}
