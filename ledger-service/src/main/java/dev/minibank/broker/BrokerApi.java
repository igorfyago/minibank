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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        route(server, "/api/portfolio", this::portfolio);
        route(server, "/api/watchlist", this::watchlist);
        route(server, "/api/link", this::link);
        route(server, "/api/audit", this::audit);
        // the chart the portfolio screen draws · served from THIS process
        // rather than fetched from the ledger's copy, because a page on :8091
        // calling :8080 is cross-origin and the cure for that is CORS, which
        // is a much bigger door than this needs. Same jar, same Redis cache,
        // no HTTP hop.
        route(server, "/api/prices/history", BrokerApi::priceHistory);
        // last, and a prefix rather than an exact path, so it never shadows
        // anything above it
        route(server, "/portfolio", BrokerApi::staticFile);

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
             .append("\",\"name\":").append(text(i.displayName()))
             .append(",\"exchange\":").append(text(i.exchange()))
             .append(",\"kind\":\"").append(i.kind())
             .append("\",\"assetCode\":\"").append(i.assetCode())
             .append("\",\"settleCcy\":\"").append(i.settleCcy())
             .append("\",\"price\":").append(money(mark(i.symbol()))).append("}");
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
            Portfolio.Quote q = quote(p.symbol());
            // an unknown price is NOT zero · valuing a holding at 0.00 and
            // reporting a 100% loss because a feed was down is a lie the
            // portfolio path already refuses to tell, and this endpoint
            // should not tell it either
            BigDecimal px = q.priced() ? q.price() : null;
            BigDecimal value = px == null ? null : p.qty().multiply(px).setScale(2, RoundingMode.HALF_UP);
            BigDecimal unrealized = value == null ? null : value.subtract(p.costBasis()).setScale(2, RoundingMode.HALF_UP);
            if (!first) b.append(',');
            first = false;
            b.append("{\"symbol\":\"").append(p.symbol())
             .append("\",\"qty\":\"").append(plain(p.qty()))
             .append("\",\"avgCost\":\"").append(plain(p.averageCost().setScale(2, RoundingMode.HALF_UP)))
             .append("\",\"costBasis\":\"").append(plain(p.costBasis().setScale(2, RoundingMode.HALF_UP)))
             .append("\",\"price\":").append(money(px))
             .append(",\"value\":").append(money(value))
             .append(",\"unrealized\":").append(money(unrealized))
             .append(",\"realized\":\"").append(plain(p.realizedPnl().setScale(2, RoundingMode.HALF_UP)))
             // where the mark came from · without this a 'fallback' price
             // (a hardcoded constant used when every feed is down) is
             // indistinguishable from a live one, and the value beside it
             // looks like a fact
             .append("\",\"priceSource\":\"").append(q.source())
             .append("\"}");
        }
        json(ex, 200, b.append(']').toString());
    }

    /**
     * THE PORTFOLIO SCREEN, in one response.
     *
     * One request because the alternative is a screen that renders in four
     * stages and shows a different total in each · the totals are computed
     * here, from unrounded numbers, rather than re-derived on the client from
     * a column of already-rounded cents.
     *
     * What is NOT in here is cash, and that is the interesting part. Net
     * liquidation is securities plus cash; cash is a balance; balances live
     * in the ledger and the broker has no way to read them. It could call the
     * ledger over HTTP, and that would turn a clean service boundary into a
     * synchronous dependency for a read that is allowed to be incomplete. So
     * cash comes back null with a note saying why, the totals are labeled
     * securities-only, and the screen can show a dash. An invented cash
     * balance would be the single most expensive lie on this page.
     */
    private void portfolio(HttpExchange ex) throws Exception {
        Long customer = caller(ex, longParam(ex, "customer"));
        if (customer == null) { json(ex, 400, "{\"error\":\"need ?customer=id\"}"); return; }

        List<Broker.Position> positions = Broker.positions(customer);
        Instant since = dayStart();
        Map<String, Broker.DayFlow> flows = Broker.flowsSince(customer, since);

        Map<String, Portfolio.Quote> quotes = new HashMap<>();
        for (Broker.Position p : positions)
            if (p.qty().signum() != 0) quotes.put(p.symbol(), quote(p.symbol()));

        Portfolio.Snapshot snap = Portfolio.build(positions, Catalog.bySymbol(), quotes, flows);
        Portfolio.Aggregate a = snap.aggregate();

        StringBuilder b = new StringBuilder();
        b.append("{\"customer\":").append(customer)
         .append(",\"baseCcy\":\"EUR\"")
         .append(",\"asOf\":\"").append(Instant.now().truncatedTo(ChronoUnit.SECONDS)).append('"')
         .append(",\"dayStart\":\"").append(since).append('"')
         .append(",\"aggregate\":{")
         .append("\"marketValue\":").append(money(a.marketValue()))
         .append(",\"costBasis\":").append(money(a.costBasis()))
         .append(",\"unrealized\":").append(money(a.unrealized()))
         .append(",\"unrealizedPct\":").append(money(a.unrealizedPct()))
         .append(",\"realized\":").append(money(a.realized()))
         .append(",\"dayChange\":").append(money(a.dayChange()))
         .append(",\"cash\":null")
         .append(",\"cashNote\":\"cash is a ledger balance and the broker cannot read the ledger's "
                 + "database · totals below are securities only\"")
         .append(",\"holdings\":").append(a.holdings())
         .append(",\"unpriced\":").append(a.unpriced())
         .append(",\"withoutPrevClose\":").append(a.withoutPrevClose())
         // provenance, so the screen can say WHY a total is missing or soft
         .append(",\"fabricated\":").append(a.fabricated())
         .append(",\"stale\":").append(a.stale())
         .append(",\"closedPositions\":").append(a.closedPositions())
         .append("},\"holdings\":[");

        boolean first = true;
        for (Portfolio.Holding h : snap.holdings()) {
            if (!first) b.append(',');
            first = false;
            b.append("{\"symbol\":\"").append(Json.esc(h.symbol())).append('"')
             .append(",\"name\":").append(text(h.name()))
             .append(",\"exchange\":").append(text(h.exchange()))
             .append(",\"kind\":").append(text(h.kind()))
             .append(",\"qty\":\"").append(plain(h.qty())).append('"')
             .append(",\"avgCost\":").append(money(h.avgCost()))
             .append(",\"price\":").append(money(h.price()))
             .append(",\"priceSource\":").append(text(h.priceSource()))
             .append(",\"value\":").append(money(h.value()))
             .append(",\"costBasis\":").append(money(h.costBasis()))
             .append(",\"unrealized\":").append(money(h.unrealized()))
             .append(",\"unrealizedPct\":").append(money(h.unrealizedPct()))
             .append(",\"realized\":").append(money(h.realized()))
             .append(",\"prevClose\":").append(money(h.prevClose()))
             .append(",\"dayChange\":").append(money(h.dayChange()))
             .append(",\"dayChangePct\":").append(money(h.dayChangePct()))
             .append(",\"dayBasis\":").append(text(h.dayBasis()))
             .append('}');
        }
        json(ex, 200, b.append("]}").toString());
    }

    /**
     * The window the day change is measured over · UTC midnight.
     *
     * Stated plainly because it does not match either instrument perfectly:
     * an equity's prior close is a US session close and bitcoin's reference
     * is a rolling 24 hours. Picking one boundary and publishing it as
     * dayStart is honest; quietly using three different ones per row and
     * aligning them in a table is not. Each holding also carries its own
     * dayBasis so the screen can say which window it actually means.
     */
    private static Instant dayStart() {
        return LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** A quote, or an admission that there is not one. Never a zero. */
    private static Portfolio.Quote quote(String symbol) {
        try {
            PriceFeed.Px px = PriceFeed.get(symbol.toLowerCase());
            if (px == null || !px.priced()) return Portfolio.Quote.none();
            return new Portfolio.Quote(px.price(), px.prevClose(), px.source());
        } catch (Exception e) {
            return Portfolio.Quote.none();
        }
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
                b.append("{\"symbol\":\"").append(s).append("\",\"price\":").append(money(mark(s))).append("}");
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
        // THE SAME PRECEDENCE RULE AS EVERY OTHER WRITE HERE, and this route
        // needed it most. Binding a session to a customer is the one call
        // that decides WHOSE BOOK a session acts on, so trusting the body's
        // customer id let anyone who knew a session string rebind it to any
        // customer they liked · Accounts.link upserts, so it would not even
        // need to win a race. It was the only write that skipped caller(),
        // which is exactly how this kind of hole survives review.
        Long customer = caller(ex, asLong(Json.num(body, "customer")));
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

    /** The chart series, straight off the feed this process already caches. */
    private static void priceHistory(HttpExchange ex) throws Exception {
        String asset = param(ex, "asset");
        if (asset == null) { json(ex, 400, "{\"error\":\"need ?asset=\"}"); return; }
        asset = asset.toLowerCase(Locale.ROOT);
        // the whitelist is the CATALOG · an instrument we do not list is not
        // an instrument we draw, and it keeps this route from becoming an
        // open proxy to Yahoo with our IP on it
        if (!Catalog.exists(asset.toUpperCase(Locale.ROOT))) {
            json(ex, 400, "{\"error\":\"not a listed instrument\"}");
            return;
        }
        json(ex, 200, "{\"asset\":\"" + Json.esc(asset) + "\",\"points\":"
                + PriceFeed.historyJson(asset) + "}");
    }

    // ------------------------------------------------------------------ static

    /**
     * The screen itself, off the classpath · the ledger's staticFile, one
     * service over.
     *
     * ROOTED AT /web-broker, NOT /web, and that is load-bearing. The broker
     * and the ledger are the same Maven module, so /web/index.html on the
     * classpath is the BANK's page; a broker rooted at /web would serve the
     * bank's UI on the broker's port, which is confusing at best and a
     * spoofing surface at worst. Its own prefix keeps the two apart.
     */
    private static void staticFile(HttpExchange ex) throws Exception {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/portfolio") || path.equals("/portfolio/")) path = "/portfolio.html";
        else path = path.substring("/portfolio".length());
        if (path.contains("..")) { send(ex, 404, "application/json", "{}".getBytes(StandardCharsets.UTF_8)); return; }
        try (InputStream in = BrokerApi.class.getResourceAsStream("/web-broker" + path)) {
            if (in == null) {
                send(ex, 404, "application/json; charset=utf-8",
                        "{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(ex, 200, contentType(path), in.readAllBytes());
        }
    }

    private static String contentType(String path) {
        return path.endsWith(".html") ? "text/html; charset=utf-8"
             : path.endsWith(".js") ? "application/javascript"
             : path.endsWith(".css") ? "text/css"
             : path.endsWith(".svg") ? "image/svg+xml"
             : path.endsWith(".png") ? "image/png"
             : path.endsWith(".json") ? "application/manifest+json"
             : "application/octet-stream";
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
        send(ex, status, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    /** The write that is not necessarily JSON · a page has to come out somehow. */
    private static void send(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.sendResponseHeaders(status, body.length);
        try (var os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    /**
     * Money as a quoted string, absence as a bare null.
     *
     * The quotes are the house style and they are not decoration: a price
     * that goes through a JSON number is a price that went through a double,
     * and the eighth decimal of a bitcoin position is real money. null is
     * unquoted because it is not a value · "null" as a string would sort,
     * concatenate and render as text, which is exactly the bug this is
     * trying to avoid.
     *
     * Trailing zeros are KEPT, unlike plain() · these arrive already scaled
     * to cents and "1005.00" is what a money column shows. plain() would
     * strip it to "1005" and the screen would grow a ragged decimal point.
     */
    private static String money(BigDecimal v) {
        return v == null ? "null" : "\"" + v.toPlainString() + "\"";
    }

    private static String text(String v) {
        return v == null ? "null" : "\"" + Json.esc(v) + "\"";
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

    /**
     * The live mark, or NULL when there isn't one.
     *
     * This used to fail soft to ZERO, which is the wrong soft failure for a
     * price: a watched symbol the feed cannot resolve rendered as "0.00",
     * which reads as "worthless" rather than "unknown". It also NPE'd · an
     * unpriced symbol returns a non-null Px whose price() is null, so this
     * handed null to plain() and took the entire watchlist panel down with a
     * 500 rather than dimming one row.
     */
    private static BigDecimal mark(String symbol) {
        try {
            PriceFeed.Px px = PriceFeed.get(symbol.toLowerCase());
            return px == null ? null : px.price();
        } catch (Exception e) {
            return null;
        }
    }

    private static String plain(BigDecimal v) {
        if (v == null) return null;
        BigDecimal s = v.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
    }
}
