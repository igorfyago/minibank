package dev.minibank.ledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * INSTRUMENTATION THAT MATCHES ITS OWN PROMISE.
 *
 * The bug this test was written for: GET /metrics emitted
 *
 *     # HELP minibank_ledger_events_total Money-path events (transfers, trades, saga legs, publishes).
 *     # TYPE minibank_ledger_events_total counter
 *
 * and then NOTHING. A HELP line, a TYPE line, and not one sample. The counter
 * named four kinds of money-path event and only ever counted two of them, both
 * on synchronous HTTP handlers. Every asynchronous step, the outbox relay
 * publishing, the applier arriving, the refund, the relocation legs, ran
 * completely uninstrumented, so the "Ledger events by kind" panel on the
 * operations dashboard could never draw anything no matter what the bank did.
 *
 * An empty panel is worse than no panel. A panel that is blank because nothing
 * is happening and a panel that is blank because nobody wired the counter look
 * identical from the outside, which is exactly why this went unnoticed.
 *
 * These tests are deliberately of two kinds:
 *
 *   1. behavioural · the counter emits samples, and the exposition it produces
 *      is well formed enough for a strict parser.
 *   2. structural · the classes on the money path actually reference Metrics.
 *      A source-level assertion is unusual, and it is the right tool here:
 *      the failure being guarded against is an omission, and an omission is
 *      invisible to any test that only exercises the code that does exist.
 */
class MetricsCoverageLessonTest {

    private static final Path SRC = Path.of("src/main/java/dev/minibank/ledger");

    private static String read(String file) throws IOException {
        return Files.readString(SRC.resolve(file));
    }

    // ------------------------------------------------------------ behaviour

    @Test
    @DisplayName("lesson 1 · a counter that is never incremented emits a header and no samples, which reads as silence")
    void counterWithNoIncrementsEmitsNoSamples() {
        // The exposition format has no way to say "this counter exists and is
        // zero" for a labelled family. No inc() call means no label set exists,
        // and a family with no label sets contributes no lines at all.
        String body = Metrics.scrape();
        assertTrue(body.contains("# TYPE minibank_ledger_events_total counter"),
                "the family is declared");
        // This is the shape the bug had. Kept as a named fact so the next
        // reader understands why a declared counter can still show nothing.
    }

    @Test
    @DisplayName("lesson 2 · once incremented, the family emits one sample line per label set")
    void incrementedCounterEmitsOneLinePerLabelSet() {
        Metrics.inc("minibank_ledger_events_total", "kind=\"unit_test_alpha\"");
        Metrics.inc("minibank_ledger_events_total", "kind=\"unit_test_alpha\"");
        Metrics.inc("minibank_ledger_events_total", "kind=\"unit_test_beta\"");

        List<String> samples = samplesOf(Metrics.scrape(), "minibank_ledger_events_total");
        assertTrue(samples.contains("minibank_ledger_events_total{kind=\"unit_test_alpha\"} 2"),
                "two increments on one label set is one line reading 2, not two lines. Got: " + samples);
        assertTrue(samples.contains("minibank_ledger_events_total{kind=\"unit_test_beta\"} 1"),
                "each distinct label set is its own line. Got: " + samples);
    }

    @Test
    @DisplayName("lesson 3 · every sample of a family sits under that family's own HELP and TYPE")
    void familiesAreContiguous() {
        Metrics.inc("minibank_ledger_events_total", "kind=\"unit_test_contiguity\"");
        Metrics.gauge("minibank_outbox_pending", "region=\"unit_test\"", 3);
        String body = Metrics.scrape();

        // A strict parser assigns a sample to the most recent TYPE line above
        // it. If a family's samples were emitted after some other family's TYPE
        // the type would be silently wrong, which is the kind of bug that only
        // shows up as a weird graph weeks later.
        String typeLine = "# TYPE minibank_ledger_events_total counter";
        int type = body.indexOf(typeLine);
        int sample = body.indexOf("minibank_ledger_events_total{kind=\"unit_test_contiguity\"}");
        assertTrue(type >= 0 && sample > type, "the sample must follow its own TYPE line");

        // from the END of this family's TYPE line, or the line matches itself
        String between = body.substring(type + typeLine.length(), sample);
        assertFalse(between.contains("# TYPE minibank_"),
                "no other family's TYPE may sit between this family's TYPE and its samples, found: " + between);
    }

    // ----------------------------------------------------------- structure

    /**
     * The HELP text is a promise to whoever reads the dashboard. This pins it,
     * so that widening the promise without widening the instrumentation fails
     * here rather than silently producing an empty panel.
     */
    @Test
    @DisplayName("lesson 4 · the money path counts every kind of event its HELP text advertises")
    void everyAdvertisedKindIsActuallyEmittedSomewhere() throws IOException {
        String metricsSrc = read("Metrics.java");
        Matcher help = Pattern.compile("# HELP minibank_ledger_events_total ([^\\\\\"]+)").matcher(metricsSrc);
        assertTrue(help.find(), "the HELP text must exist to be checked against");
        String promise = help.group(1);

        // What the HELP says the counter covers, and where each one lives.
        // If you add a word to the promise, add its call site here too.
        record Promise(String word, String file, String kindLabel) {}
        List<Promise> promises = List.of(
                new Promise("transfers", "HttpApi.java", "transfer_local"),
                new Promise("trades", "HttpApi.java", "trade"),
                new Promise("saga legs", "ShardApplier.java", "saga_arrive"),
                new Promise("publishes", "OutboxRelay.java", "outbox_publish"));

        List<String> unmet = new ArrayList<>();
        for (Promise p : promises) {
            if (!promise.contains(p.word().replace(" legs", ""))) continue;  // not promised, nothing to meet
            String src = read(p.file());
            boolean counts = src.contains("minibank_ledger_events_total")
                    && src.contains(p.kindLabel());
            if (!counts) unmet.add(p.word() + " is promised in the HELP text but " + p.file()
                    + " never emits kind=\"" + p.kindLabel() + "\"");
        }
        assertTrue(unmet.isEmpty(),
                "the HELP text promises more than the code delivers:\n  " + String.join("\n  ", unmet));
    }

    @Test
    @DisplayName("lesson 5 · the asynchronous half of the money path is instrumented, not just the HTTP handlers")
    void asynchronousMoneyPathIsInstrumented() throws IOException {
        // The original gap: everything reachable from a user's click was
        // counted, and everything the bank did on its own was not. A dashboard
        // built from that shows a bank that only works when someone is looking.
        for (String file : List.of("OutboxRelay.java", "ShardApplier.java")) {
            assertTrue(read(file).contains("Metrics."),
                    file + " sits on the money path and must report what it does. "
                            + "Without it the operations dashboard cannot see any work "
                            + "that happens after the HTTP response has already been sent.");
        }
    }

    // ------------------------------------------------------------- helpers

    private static List<String> samplesOf(String body, String family) {
        List<String> out = new ArrayList<>();
        for (String line : body.split("\n")) {
            String t = line.trim();
            if (t.startsWith(family + "{") || t.equals(family)) out.add(t);
        }
        return out;
    }
}
