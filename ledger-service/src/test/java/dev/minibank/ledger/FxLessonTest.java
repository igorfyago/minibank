package dev.minibank.ledger;

import dev.minibank.fx.FxService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE FX SERVICE · the first mechanism that lives in its own process.
 * Two lessons: the service answers with a labeled rate, and the client
 * NEVER stalls or fails when the service is gone · a hard deadline plus
 * a fallback, the circuit-breaker idea at demo scale.
 */
class FxLessonTest {

    @Test
    void lesson1_the_service_answers_with_a_labeled_rate() throws Exception {
        var server = FxService.start(18090);
        try {
            HttpResponse<String> r = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:18090/rate")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode());
            assertTrue(r.body().contains("\"pair\":\"USDEUR\""), r.body());
            String rate = r.body().replaceAll(".*\"rate\":\"([0-9.]+)\".*", "$1");
            assertTrue(new BigDecimal(rate).compareTo(new BigDecimal("0.5")) > 0
                            && new BigDecimal(rate).compareTo(new BigDecimal("1.5")) < 0,
                    "USD→EUR should be a sane rate, got " + rate);
            System.out.println("lesson 1: fx-service answered " + r.body());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void lesson2_the_client_never_stalls_when_fx_is_down() {
        long t0 = System.nanoTime();
        FxClient.Rate r = FxClient.from("http://localhost:19");   // nothing listens there
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(r.source().startsWith("fx down"), "source was: " + r.source());
        assertTrue(r.rate().signum() > 0);
        assertTrue(ms < 2000, "the deadline must bound the wait, took " + ms + "ms");
        System.out.println("lesson 2: fx down → " + r.rate() + " (" + r.source() + ") in " + ms + "ms");
    }
}
