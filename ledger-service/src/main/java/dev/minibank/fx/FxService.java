package dev.minibank.fx;

import com.sun.net.httpserver.HttpServer;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * THE FX SERVICE · minibank's first true microservice.
 *
 * Its own process, its own port, one responsibility: exchange rates.
 * No database, no ledger access, no shared state with the bank · the only
 * contract between them is HTTP. In production compose it runs as its own
 * container; the single-JVM dev run embeds it on :8090 so `mvn exec:java`
 * still boots everything with one command.
 *
 * Upstream: frankfurter.app (keyless), cached 60s, static fallback, and a
 * source label · the same honesty contract as the price feed.
 */
public final class FxService {

    public record Rate(BigDecimal rate, String source) {}

    private static final BigDecimal FALLBACK = new BigDecimal("0.88");
    // followRedirects matters: the JDK client ignores 301s by default and
    // frankfurter moved from .app to .dev behind exactly such a redirect
    private static final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(3)).build();
    private static volatile Object[] cache;   // [Rate, atMillis]

    private FxService() {}

    /** USD→EUR, cached 60s · live, cached or fallback, always labeled. */
    public static Rate usdToEur() {
        Object[] hit = cache;
        if (hit != null && System.currentTimeMillis() - (long) hit[1] < 60_000) return (Rate) hit[0];
        Rate r;
        try {
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder(
                            URI.create("https://api.frankfurter.dev/v1/latest?from=USD&to=EUR"))
                    .timeout(Duration.ofSeconds(4)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) throw new IllegalStateException("HTTP " + resp.statusCode());
            Matcher m = Pattern.compile("\"EUR\"\\s*:\\s*([0-9.]+)").matcher(resp.body());
            if (!m.find()) throw new IllegalStateException("no EUR in response");
            r = new Rate(new BigDecimal(m.group(1)), "live");
        } catch (Exception e) {
            r = hit != null ? new Rate(((Rate) hit[0]).rate(), "cached") : new Rate(FALLBACK, "fallback");
        }
        cache = new Object[]{r, System.currentTimeMillis()};
        return r;
    }

    /** The service itself: GET /rate and GET /health, a virtual thread per request. */
    public static HttpServer start(int port) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress(port), 0);
        s.createContext("/rate", ex -> {
            Rate r = usdToEur();
            byte[] b = ("{\"pair\":\"USDEUR\",\"rate\":\"" + r.rate().toPlainString() +
                    "\",\"source\":\"" + r.source() + "\"}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        s.createContext("/health", ex -> {
            byte[] b = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        s.start();
        return s;
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("FX_PORT", "8090"));
        start(port);
        System.out.println("fx-service up: http://localhost:" + port + "/rate");
        Thread.currentThread().join();
    }
}
