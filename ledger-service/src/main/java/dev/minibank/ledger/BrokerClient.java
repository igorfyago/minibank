package dev.minibank.ledger;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * THE BANK'S VIEW OF THE BROKER · how a tile click becomes an order.
 *
 * WHY THIS EXISTS AT ALL. The bank used to acquire assets by writing them
 * straight into the ledger (Products.trade). That made the bank a second
 * place an asset position could be born, and the broker never heard about
 * it · which is exactly how the two books came to disagree. There is now one
 * way to acquire an asset in this system: place an order with the broker,
 * let it fill, let the fill settle. This class is the bank's end of that.
 *
 * WHY AN HTTP HOP RATHER THAN A CORS HEADER. The obvious alternative was to
 * let the browser call the broker directly, which is cross-origin (the bank
 * is served from :8080, the broker from :8091) and therefore needs CORS on
 * the broker. BrokerApi already refused to open that door once, deliberately,
 * for the price-history route · and the broker is the service that holds the
 * order book and the cost basis. Putting a permissive origin header on the
 * service that holds the books, so that a button can be wired up, is a large
 * door opened for a small reason. The ledger calling the broker server-side
 * needs no CORS anywhere: the browser only ever talks to the origin it was
 * served from. FxClient and PriceFeed already make exactly this kind of call
 * out of the ledger process, so this is an established shape here, not a new
 * one.
 *
 * WHY NO FALLBACK, unlike FxClient. A missing FX rate can fall back to the
 * last good one because a slightly stale rate is better than a stalled
 * payment. There is no equivalent for an order: the fallback for "the broker
 * did not answer" would be to acquire the asset some other way, and that
 * other way is precisely the write path this change exists to delete. So a
 * broker that cannot be reached means the trade did not happen, and the
 * customer is told exactly that.
 */
public final class BrokerClient {

    /** What the broker said about an order we asked it to place. */
    public record Placed(String status, String orderId, String symbol, String side,
                         String venue, String error) {

        /** Did the venue take it? 'filled' and 'accepted' both mean yes. */
        public boolean accepted() {
            return "filled".equals(status) || "accepted".equals(status) || "settled".equals(status);
        }
    }

    /** The broker is unreachable or answered with something unusable. This is
     *  never softened into a fallback · see the class comment. */
    public static final class BrokerUnavailable extends RuntimeException {
        public BrokerUnavailable(String message) {
            super(message);
        }
    }

    private static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    private BrokerClient() {}

    /**
     * Where the broker is · resolved per call, not captured at class load.
     *
     * The system property is checked first so a test can point this at a
     * broker it started on an ephemeral port. That ordering matters: a static
     * final field initialised from the environment cannot be redirected after
     * the class loads, which would have left the one endpoint this change is
     * about as the one endpoint with no end-to-end test.
     */
    static String base() {
        String override = System.getProperty("broker.url");
        if (override != null && !override.isBlank()) return override;
        return System.getenv().getOrDefault("BROKER_URL", "http://localhost:8091");
    }

    /**
     * Place a market order for a EUR amount.
     *
     * clientOrderId is the caller's idempotency key and it is the SAME value
     * the bank used to use as its ledger txId. That continuity is the point:
     * the browser already generates one per click and already retries with
     * it, the broker already has a UNIQUE index on it, so a retried click is
     * one order for exactly the reason a retried transfer was one transfer.
     * No new idempotency mechanism was invented, because both services
     * already had the right one and it was the same one.
     */
    public static Placed placeNotional(String clientOrderId, long customerId, String symbol,
                                       String side, BigDecimal eur) {
        return place(clientOrderId, customerId, symbol, side, "notional", eur);
    }

    /**
     * Place a market order for an exact NUMBER OF UNITS.
     *
     * This exists for one case that a euro amount cannot express: closing a
     * position out. "Sell all" as a notional asks the venue to divide the
     * position's current euro value by a price that has moved since it was
     * read, which lands fractionally ABOVE the holding and the broker rejects
     * the order whole ("cannot sell 0.00088516 BTC: position is 0.00088427").
     * The quantity is a number the caller already holds exactly, so it is sent
     * exactly rather than round-tripped through a price twice.
     */
    public static Placed placeQty(String clientOrderId, long customerId, String symbol,
                                  String side, BigDecimal units) {
        return place(clientOrderId, customerId, symbol, side, "qty", units);
    }

    private static Placed place(String clientOrderId, long customerId, String symbol,
                                String side, String sizeField, BigDecimal size) {
        String body = "{\"clientOrderId\":\"" + Json.esc(clientOrderId)
                + "\",\"customer\":" + customerId
                + ",\"symbol\":\"" + Json.esc(symbol.toUpperCase())
                + "\",\"side\":\"" + Json.esc(side)
                + "\",\"type\":\"market\""
                + ",\"" + sizeField + "\":\"" + size.toPlainString() + "\"}";
        return post(base() + "/api/orders", body);
    }

    /** The mechanism, with the base URL named · what the lesson test drives. */
    static Placed post(String url, String body) {
        HttpResponse<String> resp;
        try {
            resp = http.send(HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(5))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            Metrics.inc("minibank_broker_orders_total", "outcome=\"unreachable\"");
            throw new BrokerUnavailable("the broker did not answer · " + e);
        }
        String payload = resp.body();
        String error = Json.str(payload, "error");
        // 400 and 409 are the broker declining an order it understood: an
        // unlisted symbol, a size it cannot fill. That is an answer, not an
        // outage, and the customer should see the broker's own words.
        if (resp.statusCode() >= 500 || (resp.statusCode() != 200 && error == null)) {
            Metrics.inc("minibank_broker_orders_total", "outcome=\"error\"");
            throw new BrokerUnavailable("the broker answered HTTP " + resp.statusCode());
        }
        Placed p = new Placed(Json.str(payload, "result"), Json.str(payload, "id"),
                Json.str(payload, "symbol"), Json.str(payload, "side"),
                Json.str(payload, "venue"), error);
        Metrics.inc("minibank_broker_orders_total",
                "outcome=\"" + (p.accepted() ? "placed" : "rejected") + "\"");
        return p;
    }
}
