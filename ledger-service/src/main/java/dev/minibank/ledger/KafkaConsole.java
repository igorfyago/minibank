package dev.minibank.ledger;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * KAFKA CONSOLE — the broker itself, not a mirror of it.
 *
 * The records come from a real KafkaConsumer that assigns the payments
 * topic's partitions, seeks near the end and polls: real offsets, real
 * timestamps, the exact bytes. The consumer-group table comes from the
 * ADMIN API: committed offsets per group vs the end offset = LAG — the
 * number every Kafka operator watches. Cached a few seconds; the page
 * polls politely.
 */
public final class KafkaConsole {

    private static volatile Object[] cache;   // [json, atMillis]

    private KafkaConsole() {}

    public static String consoleJson(String bootstrap) {
        Object[] hit = cache;
        if (hit != null && System.currentTimeMillis() - (long) hit[1] < 4000) return (String) hit[0];
        String out;
        try {
            out = build(bootstrap);
        } catch (Exception e) {
            out = "{\"error\":\"" + Json.esc(String.valueOf(e.getMessage())) + "\",\"records\":[],\"groups\":[]}";
        }
        cache = new Object[]{out, System.currentTimeMillis()};
        return out;
    }

    private static String build(String bootstrap) throws Exception {
        Properties p = new Properties();
        p.put("bootstrap.servers", bootstrap);
        p.put("max.poll.records", "500");

        List<String> recs = new ArrayList<>();
        Map<TopicPartition, Long> ends;
        // deserializer INSTANCES, not class names: this runs on a request's
        // virtual thread whose context classloader can't resolve the name
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p,
                new org.apache.kafka.common.serialization.StringDeserializer(),
                new org.apache.kafka.common.serialization.StringDeserializer())) {
            var infos = consumer.partitionsFor("payments", Duration.ofSeconds(3));
            if (infos == null || infos.isEmpty())
                return "{\"records\":[],\"groups\":[]}";
            List<TopicPartition> tps = infos.stream()
                    .map(i -> new TopicPartition(i.topic(), i.partition())).toList();
            consumer.assign(tps);
            ends = consumer.endOffsets(tps);
            Map<TopicPartition, Long> begins = consumer.beginningOffsets(tps);
            for (TopicPartition tp : tps) {
                consumer.seek(tp, Math.max(begins.get(tp), ends.get(tp) - 40));
            }
            long deadline = System.currentTimeMillis() + 1800;
            List<ConsumerRecord<String, String>> got = new ArrayList<>();
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(300));
                polled.forEach(got::add);
                boolean done = true;
                for (TopicPartition tp : tps) {
                    if (consumer.position(tp) < ends.get(tp)) { done = false; break; }
                }
                if (done) break;
            }
            got.sort((a, b2) -> Long.compare(b2.offset(), a.offset()));
            for (ConsumerRecord<String, String> r : got.subList(0, Math.min(got.size(), 40))) {
                recs.add("{\"partition\":" + r.partition() + ",\"offset\":" + r.offset() +
                        ",\"ts\":" + r.timestamp() +
                        ",\"key\":\"" + Json.esc(r.key() == null ? "" : r.key()) +
                        "\",\"value\":\"" + Json.esc(r.value() == null ? "" : r.value()) + "\"}");
            }
        }

        List<String> groups = new ArrayList<>();
        Properties ap = new Properties();
        ap.put("bootstrap.servers", bootstrap);
        try (AdminClient admin = AdminClient.create(ap)) {
            for (String g : new String[]{"shard-applier", "notifications"}) {
                try {
                    Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> committed =
                            admin.listConsumerGroupOffsets(g).partitionsToOffsetAndMetadata()
                                 .get(3, java.util.concurrent.TimeUnit.SECONDS);
                    Map<TopicPartition, Long> byTp = new HashMap<>();
                    committed.forEach((tp, om) -> {
                        if ("payments".equals(tp.topic()) && om != null) byTp.put(tp, om.offset());
                    });
                    for (var e : byTp.entrySet()) {
                        long end = ends.getOrDefault(e.getKey(), e.getValue());
                        groups.add("{\"group\":\"" + g + "\",\"partition\":" + e.getKey().partition() +
                                ",\"committed\":" + e.getValue() + ",\"end\":" + end +
                                ",\"lag\":" + Math.max(0, end - e.getValue()) + "}");
                    }
                } catch (Exception ignored) {
                    // group not yet formed — nothing to report
                }
            }
        }
        return "{\"records\":[" + String.join(",", recs) + "],\"groups\":[" + String.join(",", groups) + "]}";
    }
}
