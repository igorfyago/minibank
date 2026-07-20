package dev.minibank.ledger;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WHICH THREAD RUNS A CONSUMER LOOP, and why it is the one non-obvious thread
 * choice in this service.
 *
 * Virtual threads are for work that BLOCKS AND UNMOUNTS: many short request
 * handlers, each parked on a socket, freeing its carrier while it waits. The
 * HTTP server uses them for exactly that and it is right to.
 *
 * A Kafka consumer loop is the opposite shape. It is one long-lived thread that
 * never finishes, and KafkaConsumer guards its state with synchronized blocks,
 * so blocking inside one pins the carrier underneath rather than releasing it.
 * The production box has 2 vCPUs, so the scheduler starts with 2 carriers, and
 * this JVM runs THREE such loops.
 *
 * The symptom was not a crash and not an error in any log. Notifications
 * arrived tens of seconds after the commit that caused them while Kafka
 * consumer lag read zero throughout, and two events for different transactions
 * landed 37 milliseconds apart after a 22 second silence: a loop that was not
 * being scheduled, then draining everything at once. The scheduler had grown
 * ForkJoinPool-1 to six threads on a two core box, which is compensation for
 * pinned carriers rather than throughput.
 *
 * This is pinned as a test because the fix is one word in a constructor and
 * looks like a downgrade to anyone tidying up later.
 */
class ConsumerThreadLessonTest {

    private static final List<String> POLL_LOOPS = List.of(
            "NotificationsConsumer.java", "ShardApplier.java", "Settlement.java");

    @Test
    void everyKafkaPollLoopRunsOnAPlatformThread() {
        for (String file : POLL_LOOPS) {
            String src = read(file);
            assertTrue(src.contains("Thread.ofPlatform()"),
                    file + " runs a KafkaConsumer loop and must own a platform thread");
            assertFalse(src.contains("Thread.startVirtualThread"),
                    file + " must not put a pinning poll loop back on a carrier");
        }
    }

    @Test
    void theLoopsAreDaemonsSoTheyDoNotHoldTheJvmOpen() {
        // A platform thread keeps the JVM alive where a virtual thread does not,
        // so moving off virtual threads quietly changes shutdown unless they are
        // daemons. This is the regression that comes free with the fix.
        for (String file : POLL_LOOPS) {
            assertTrue(read(file).contains(".daemon()"),
                    file + " must not keep the JVM alive after main returns");
        }
    }

    @Test
    void theyAreNamedSoAThreadDumpIsReadable() {
        // The evidence that found this bug was a thread listing. Unnamed threads
        // would have made it unreadable.
        for (String file : POLL_LOOPS) {
            assertTrue(read(file).contains(".name("),
                    file + " must name its thread, for the next person reading a dump");
        }
    }

    private static String read(String simpleName) {
        Path p = Path.of("src/main/java/dev/minibank/ledger", simpleName);
        try {
            return Files.readString(p);
        } catch (Exception e) {
            throw new AssertionError("could not read " + p + ": " + e.getMessage(), e);
        }
    }
}
