package dev.minibank.broker;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.minibank.ledger.Json;
import dev.minibank.ledger.PriceFeed;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;

/**
 * THE BROKER'S HTTP CONTRACT.
 *
 * Same shape as the rest of the bank: the JDK's HttpServer, a virtual thread
 * per request, hand-written JSON, no framework. The service is reachable
 * only by name inside the compose network and through the app, which is why
 * it carries no auth of its own · the honest note being that "not exposed"
 * is a deployment property, not a security property, and the day this is
 * public it needs a real gate. Accounts.link is where that goes.
 */
public final class BrokerApi {

    private final Broker broker;
    private final CallerIdentity identity;

    /** Anonymous: the behaviour this service shipped with. */
    public BrokerApi(Broker broker) {
        this(broker, CallerIdentity.ANONYMOUS);
    }

    public BrokerApi(Broker broker, CallerIdentity identity) {
        this.broker = broker;
        this.identity = identity;
    }

    /**
     * Which book this request is allowed to touch.
     *
     * A token identifies its own customer and that wins; without one the
     * query parameter stands, exactly as before. See CallerIdentity.resolve
     * for why this is settled now rather than on activation day.
     */
    private Long caller(HttpExchange ex, Long requested) {
        Optional<Long> identified = identity.customerFor(ex.getRequestHeaders().getFirst("Authorization"));
        if (identified.isPresent()) return identified.get();
        return requested;
    }

    public HttpServer start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        route(server, "/health", ex -> json(ex, 200, "{\"ok\":true,\"venue\":\"" + broker.venue().name() + "\"}"));
        route(server, "/api/instruments", this::instruments);
        route(server, "/api/orders", this::orders);
        route(server, "/api/positions", this::positions);
        route(server, "/api/watchlist", this::watchlist);
        route(server, "/api/link", this::link);
        route(server, "/api/audit", this::audit);

        server.start();
        return server;
    }

    // ------------------------------------------------------------------ routes

    private void instruments(HttpExchange ex) throws Exception {
        StringBuilder b = new StringBuilder("[");
        boolean first = true;
        for (Catalog.Instrument i : Catalog.all()) {
            if (!first) b.append(',');
            first = false;
            b.append("{\"symbol\":\"").append(i.symbol())
             .append("\",\"kind\":\"").append(i.kind())
             .append("\",\"assetCode\":\"").append(i.assetCode())
             .append("\",\"settleCcy\":\"").append(i.settleCcy())
             .append("\",\"price\":\"").append(plain(mark(i.symbol()))).append("\"}");
        }
        json(ex, 200, b.append(']').toString());
    }

    /** POST places an order, GET lists them. */
    private void orders(HttpExchange ex) throws Exception {
        if ("GET".equals(ex.getRequestMethod())) {
            Long customer = caller(ex, longParam(ex, "customer"));
            if (customer == null) { json(ex, 400, "{\"error\":\"need ?customer=id\"}"); return; }
            StringBuilder b = new StringBuilder("[");
            boolean first = true;
            for (Broker.Order o : Broker.orders(customer, 50)) {
                if (!first) b.append(',');
                first = false;
                b.append("{\"id\":\"").append(o.id())
                 .append("\",\"clientOrderId\":\"").append(Json.esc(o.clientOrderId()))
                 .append("\",\"symbol\":\"").append(o.symbol())
                 .append("\",\"side\":\"").append(o.side())
                 .append("\",\"status\":\"").append(o.status())
                 .append("\",\"venue\":\"").append(Json.esc(o.venueName()))
                 .append("\",\"qty\":").append(o.qty() == null ? "null" : "\"" + plain(o.qty()) + "\"")
                 .append(",\"notional\":").append(o.notional() == null ? "null" : "\"" + plain(o.notional()) + "\"")
                 .append(",\"rejectReason\":").append(o.rejectReason() == null
                         ? "null" : "\"" + Json.esc(o.rejectReason()) + "\"")
                 .append('}');
            }
            json(ex, 200, b.append(']').toString());
            return;
        }
        if (!"POST".equals(ex.getRequestMethod())) { json(ex, 405, "{\"error\":\"GET or POST\"}"); return; }

        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String clientOrderId = Json.str(body, "clientOrderId");
        String symbol = Json.str(body, "symbol");
        String side = Json.str(body, "side");
        String type = Json.str(body, "type");
        Long customer = caller(ex, asLong(Json.num(body, "customer")));
        if (clientOrderId == null || symbol == null || side == null || customer == null) {
            json(ex, 400, "{\"error\":\"need clientOrderId, customer, symbol, side\"}");
            return;
        }
        if (!"buy".equals(side) && !"sell".equals(side)) {
            json(ex, 400, "{\"error\":\"side must be buy or sell\"}");
            return;
        }
        symbol = symbol.toUpperCase();
        if (!Catalog.exists(symbol)) { json(ex, 400, "{\"error\":\"not a listed instrument: " + Json.esc(symbol) + "\"}"); return; }

        BigDecimal qty = decimal(Json.str(body, "qty"));
        BigDecimal notional = decimal(Json.str(body, "notional"));
        if (qty == null && notional == null) {
            json(ex, 400, "{\"error\":\"need qty or notional\"}");
            return;
        }
        BigDecimal limitPx = decimal(Json.str(body, "limitPx"));
        String orderType = type == null ? "market" : type;
        if ("limit".equals(orderType) && limitPx == null) {
            json(ex, 400, "{\"error\":\"a limit order needs limitPx\"}");
            return;
        }

        try {
            Broker.Order o = broker.place(clientOrderId, customer, symbol, side,
                    qty, notional, orderType, limitPx);
            json(ex, 200, "{\"result\":\"" + o.status() + "\",\"id\":\"" + o.id()
                    + "\",\"symbol\":\"" + o.symbol() + "\",\"side\":\"" + o.side()
                    + "\",\"venue\":\"" + Json.esc(o.venueName()) + "\""
                    + (o.rejectReason() == null ? "" : ",\"error\":\"" + Json.esc(o.rejectReason()) + "\"")
                    + "}");
        } catch (IllegalArgumentException e) {
            json(ex, 409, "{\"result\":\"rejected\",\"error\":\"" + Json.esc(e.getMessage()) + "\"}");
        }
    }

    /**
     * Positions, marked to the live price.
     *
     * The stored numbers are qty, cost basis and realised P&L · all facts
     * about the past. Market value and unrealised P&L are computed HERE, on
     * every read, because they are opinions about right now and storing an
     * opinion is how a stale one gets believed.
     */
    private void positions(HttpExchange ex) throws Exception {
        Long customer = caller(ex, longParam(ex, "customer"));
        if (customer == null) { json(ex, 400, "{\"error\":\"need ?customer=id\"}"); return; }

        StringBuilder b = new StringBuilder("[");
        boolean first = true;
        for (Broker.Position p : Broker.positions(customer)) {
            BigDecimal px = mark(p.symbol());
            BigDecimal value = p.qty().multiply(px).setScale(2, RoundingMode.HALF_UP);
            BigDecimal unrealized = value.subtract(p.costBasis()).setScale(2, RoundingMode.HALF_UP);
            if (!first) b.append(',');
            first = false;
            b.append("{\"symbol\":\"").append(p.symbol())
             .append("\",\"qty\":\"").append(plain(p.qty()))
             .append("\",\"avgCost\":\"").append(plain(p.averageCost().setScale(2, RoundingMode.HALF_UP)))
             .append("\",\"costBasis\":\"").append(plain(p.costBasis().setScale(2, RoundingMode.HALF_UP)))
             .append("\",\"price\":\"").append(plain(px))
             .append("\",\"value\":\"").append(plain(value))
             .append("\",\"unrealized\":\"").append(plain(unrealized))
             .append("\",\"realized\":\"").append(plain(p.realizedPnl().setScale(2, RoundingMode.HALF_UP)))
             .append("\"}");
        }
        json(ex, 200, b.append(']').toString());
    }

    private void watchlist(HttpExchange ex) throws Exception {
        if ("GET".equals(ex.getRequestMethod())) {
            Long customer = caller(ex, longParam(ex, "customer"));
            if (customer == null) { json(ex, 400, "{\"error\":\"need ?customer=id\"}"); return; }
            StringBuilder b = new StringBuilder("[");
            boolean first = true;
            for (String s : Accounts.watchlist(customer)) {
                if (!first) b.append(',');
                first = false;
                b.append("{\"symbol\":\"").append(s).append("\",\"price\":\"").append(plain(mark(s))).append("\"}");
            }
            json(ex, 200, b.append(']').toString());
            return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Long customer = caller(ex, asLong(Json.num(body, "customer")));
        String symbol = Json.str(body, "symbol");
        String action = Json.str(body, "action");
        if (customer == null || symbol == null) { json(ex, 400, "{\"error\":\"need customer, symbol\"}"); return; }
        if ("remove".equals(action)) Accounts.unwatch(customer, symbol);
        else Accounts.watch(customer, symbol);
        json(ex, 200, "{\"result\":\"ok\"}");
    }

    private void link(HttpExchange ex) throws Exception {
        if ("GET".equals(ex.getRequestMethod())) {
            String session = param(ex, "session");
            if (session == null) { json(ex, 400, "{\"error\":\"need ?session=\"}"); return; }
            Long customer = Accounts.customerFor(session);
            json(ex, 200, customer == null ? "{\"customer\":null}" : "{\"customer\":" + customer + "}");
            return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String session = Json.str(body, "session");
        Long customer = asLong(Json.num(body, "customer"));
        if (session == null || customer == null) { json(ex, 400, "{\"error\":\"need session, customer\"}"); return; }
        Accounts.link(session, customer);
        json(ex, 200, "{\"result\":\"ok\",\"customer\":" + customer + "}");
    }

    /** The projection audit, over HTTP · the same number the X-ray shows for the ledger. */
    private void audit(HttpExchange ex) throws Exception {
        List<String> drift = Broker.audit();
        StringBuilder b = new StringBuilder("{\"drifted\":").append(drift.size()).append(",\"detail\":[");
        for (int i = 0; i < drift.size(); i++) {
            if (i > 0) b.append(',');
            b.append('"').append(Json.esc(drift.get(i))).append('"');
        }
        json(ex, 200, b.append("]}").toString());
    }

    // ------------------------------------------------------------------ plumbing

    private interface Handler { void handle(HttpExchange ex) throws Exception; }

    private static void route(HttpServer server, String path, Handler h) {
        server.createContext(path, ex -> {
            try (InputStream ignored = ex.getRequestBody()) {
                h.handle(ex);
            } catch (Exception e) {
                try {
                    json(ex, 500, "{\"error\":\"" + Json.esc(String.valueOf(e.getMessage())) + "\"}");
                } catch (IOException io) {
                    ex.close();
                }
            }
        });
    }

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, out.length);
        try (var os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    private static String param(HttpExchange ex, String name) {
        String q = ex.getRequestURI().getQuery();
        if (q == null) return null;
        for (String p : q.split("&"))
            if (p.startsWith(name + "=")) return p.substring(name.length() + 1);
        return null;
    }

    private static Long longParam(HttpExchange ex, String name) {
        String v = param(ex, name);
        try {
            return v == null ? null : Long.parseLong(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long asLong(String v) {
        try {
            return v == null || v.isBlank() ? null : Long.valueOf(new BigDecimal(v).longValueExact());
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal decimal(String v) {
        try {
            return v == null || v.isBlank() ? null : new BigDecimal(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** the live mark · fails soft to zero rather than failing the whole page */
    private static BigDecimal mark(String symbol) {
        try {
            PriceFeed.Px px = PriceFeed.get(symbol.toLowerCase());
            return px == null ? BigDecimal.ZERO : px.price();
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private static String plain(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
    }
}
