package dev.minibank.ledger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A TRACE MUST NOT SHOW AN EFFECT BEFORE ITS CAUSE · AND NO CLOCK CAN PROMISE THAT.
 *
 * The sibling lesson (TraceCausalityLessonTest) fixed one half of this: published_at
 * is now the instant the BROKER ACKED, captured by the relay, instead of the instant
 * the outbox row happened to be marked one round trip later. That fix is correct and
 * it is still in place. The inversion survived it, on the live site:
 *
 *   eu             departed · money into the pipe            12:34:39
 *   uk             arrived  · money out of the pipe          +113ms
 *   eu             relay -> Kafka (broker acked, then marked) +10ms
 *   notifications  notification stored                        +11ms
 *
 * The arrival is still stamped BEFORE the publish that caused it, because the OTHER
 * side of the comparison is wrong in two independent ways, and the second one cannot
 * be fixed by any choice of clock.
 *
 * ONE · the arrival is stamped with Postgres now(), which is TRANSACTION time.
 * transactions.created_at defaults to now(), and now() is transaction_timestamp():
 * frozen at BEGIN, no matter how long the transaction then runs. Shard.arrive()
 * BEGINs, claims the txId, and then takes `SELECT ... FOR UPDATE` on IN_TRANSIT ·
 * the ONE row every saga on that shard must serialise through. On a busy region it
 * waits there. The row it finally commits still carries the instant it began, so an
 * arrival that genuinely happened at T+600 is recorded as having happened at T.
 *
 * TWO · and this is the one no clock fixes: "the broker acked" and "the consumer can
 * see the message" are not the same instant, and they are not even in a guaranteed
 * order relative to each other. Both race away from the SAME broker-side commit, in
 * opposite directions · the ack travels back to the eu producer, the record travels
 * out to the uk applier. Nothing makes the return leg shorter than the delivery leg.
 * The applier can therefore begin, and finish, before producer.send().get() has
 * returned in eu. Add that the publish instant is read from the eu JVM's clock while
 * the arrival instant is read from the uk database server's clock, and the comparison
 * is between two readings that were never on one timeline to begin with.
 *
 * So this lesson does NOT assert that the timestamps come out ordered. It asserts the
 * thing that is actually true and actually knowable: the ORDER OF THE STEPS the trace
 * emits must follow the saga's causal structure. depart causes the publish, the
 * publish causes the arrival. That order is known from the code, not discovered from
 * a clock, and it must survive timestamps that disagree with it.
 *
 * The reproduction below is not a contrivance. It holds the IN_TRANSIT row · exactly
 * what a second concurrent saga on the uk shard does · so the applier's transaction
 * BEGINs, stamps itself, and then waits, while the relay records its ack in the gap.
 * That is the production race, made deterministic.
 *
 * Requires: docker compose up -d --wait
 */
class TraceCausalOrderLessonTest {

    static final long IGOR = 10, COCO = 11;
    static final int EU = 0, UK = 1;
    static final String KAFKA = "localhost:9092";

    static HttpServer bank;

    @BeforeAll
    static void boot() throws Exception {
        Shards.boot(
                System.getenv().getOrDefault("MINIBANK_SHARD0_URL", "jdbc:postgresql://localhost:5434/minibank"),
                System.getenv().getOrDefault("MINIBANK_SHARD1_URL", "jdbc:postgresql://localhost:5435/minibank"),
                "minibank", "minibank", 5);
        for (Shard s : Shards.all()) s.createSchema();
        Directory.createOwnDatabase();
        Notifications.createOwnDatabase();
        Shards.nameRegions("eu", "uk");
        Shards.setResolver(Directory::shardOf);
        bank = HttpApi.start(0);
    }

    @AfterAll
    static void unplug() {
        if (bank != null) bank.stop(0);
        Shards.setResolver(null);   // the stage-5 lessons keep their arithmetic router
    }

    @BeforeEach
    void freshWorld() throws Exception {
        Fixtures.resetShards();
        Fixtures.resetDirectory();
        Directory.register(IGOR, "igor", EU);
        Directory.register(COCO, "coco", UK);
        Shards.forCustomer(IGOR).createCustomer(IGOR, "igor");
        Shards.forCustomer(COCO).createCustomer(COCO, "coco");
        Shards.forCustomer(IGOR).transferLocal(UUID.randomUUID(), Shard.WORLD, IGOR, eur("500.00"));
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("the trace orders the arrival AFTER the publish that caused it, even when the recorded instants say otherwise")
    void arrivalNeverPrecedesThePublishThatCausedIt() throws Exception {
        UUID tx = UUID.randomUUID();
        Shards.forCustomer(IGOR).depart(tx, IGOR, COCO, eur("30.00"));
        String payload = Fixtures.outboxEvent(Shards.s(EU), "departed:" + tx).payload();

        Shard uk = Shards.s(UK);
        Thread applier;
        try (Connection hold = uk.open()) {
            // A second saga on the uk shard, holding the row every saga needs.
            hold.setAutoCommit(false);
            try (var ps = hold.prepareStatement("SELECT balance FROM accounts WHERE id = ? FOR UPDATE")) {
                ps.setLong(1, Shard.IN_TRANSIT);
                ps.executeQuery();
            }

            // The applier gets the message and starts work. It BEGINs (stamping
            // transactions.created_at with now(), the transaction clock), claims
            // the txId, and then blocks on IN_TRANSIT.
            applier = Thread.startVirtualThread(() -> {
                try {
                    ShardApplier.handle(payload);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            Thread.sleep(700);   // it is now begun, stamped, and waiting

            // ONLY NOW does the relay observe its ack. In production this gap is
            // the ack's return trip to eu; here it is a lock. Either way the
            // recorded publish instant lands AFTER the recorded arrival instant.
            try (OutboxRelay relay = new OutboxRelay(KAFKA, Shards.s(EU)::open)) {
                relay.publishPending();
            }

            hold.rollback();     // release · the arrival completes and commits
        }
        applier.join();

        List<String> order = stepsOf(tx);
        int published = order.indexOf("published");
        int arrived = order.indexOf("arrive");

        assertTrue(published >= 0, "the trace must contain the publish step · got " + order);
        assertTrue(arrived >= 0, "the trace must contain the arrival step · got " + order);
        assertTrue(published < arrived,
                "the arrival is CAUSED by the publish, so the trace must never place it first. "
                        + "Got the steps in this order: " + order + ". Read literally, uk consumed a "
                        + "message eu had not sent. The X-ray animates this list in the order it "
                        + "arrives, so this draws money landing in another region before it left.");
    }

    // ------------------------------------------------------------------
    /** The step names of /api/xray/trace, in the order the endpoint emits them. */
    private static List<String> stepsOf(UUID tx) throws Exception {
        HttpResponse<String> res = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + bank.getAddress().getPort() + "/api/xray/trace?tx=" + tx)).build(),
                HttpResponse.BodyHandlers.ofString());
        List<String> steps = new ArrayList<>();
        Matcher m = Pattern.compile("\"step\":\"([^\"]+)\"").matcher(res.body());
        while (m.find()) steps.add(m.group(1));
        return steps;
    }

    private static BigDecimal eur(String s) {
        return new BigDecimal(s);
    }
}
