package dev.minibank.ledger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * STAGE 3 — THE BANK GETS A FACE. Raw JDK HttpServer, no framework.
 *
 * THE JAVA 21 POINT: the server runs one VIRTUAL THREAD per request.
 * Virtual threads make blocking code cheap — a thread waiting on JDBC or
 * Kafka costs almost nothing, so we write simple blocking handlers and
 * still scale. This is the modern answer to "how do you handle 10K
 * concurrent requests in Java" — not callback spaghetti, not reactive
 * frameworks: cheap threads.
 */
public final class HttpApi {

    private HttpApi() {}

    public static HttpServer start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());  // one virtual thread per request

        server.createContext("/api/accounts", ex -> handle(ex, HttpApi::accounts));
        server.createContext("/api/transfer", ex -> handle(ex, HttpApi::transfer));
        server.createContext("/api/notifications", ex -> handle(ex, HttpApi::notifications));
        server.createContext("/api/xray/summary", ex -> handle(ex, HttpApi::xraySummary));
        server.createContext("/api/xray/entries", ex -> handle(ex, HttpApi::xrayEntries));
        server.createContext("/api/xray/outbox", ex -> handle(ex, HttpApi::xrayOutbox));
        server.createContext("/", ex -> handle(ex, HttpApi::staticFile));

        server.start();
        return server;
    }

    // ------------------------------------------------------------------ app

    private static Response accounts(HttpExchange ex) throws Exception {
        StringBuilder b = new StringBuilder("[");
        try (Connection c = Db.open(); var st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, owner, kind, balance FROM accounts ORDER BY id")) {
            boolean first = true;
            while (rs.next()) {
                if (!first) b.append(',');
                first = false;
                b.append("{\"id\":").append(rs.getLong(1))
                 .append(",\"owner\":\"").append(Json.esc(rs.getString(2)))
                 .append("\",\"kind\":\"").append(rs.getString(3))
                 .append("\",\"balance\":\"").append(rs.getBigDecimal(4).toPlainString()).append("\"}");
            }
        }
        return Response.json(200, b.append(']').toString());
    }

    private static Response transfer(HttpExchange ex) throws Exception {
        if (!"POST".equals(ex.getRequestMethod())) return Response.json(405, "{\"error\":\"POST only\"}");
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        // Money travels as a STRING in JSON. Floating point does not do money:
        // 0.1 + 0.2 != 0.3 in doubles. BigDecimal from string, always.
        String txId = Json.str(body, "txId");
        String from = Json.num(body, "from");
        String to = Json.num(body, "to");
        String amount = Json.str(body, "amount");
        if (txId == null || from == null || to == null || amount == null)
            return Response.json(400, "{\"error\":\"need txId, from, to, amount\"}");

        try {
            var result = Ledger.transfer(UUID.fromString(txId),
                    Long.parseLong(from), Long.parseLong(to), new BigDecimal(amount));
            String kind = switch (result) {
                case Ledger.Ok ok -> "ok";
                case Ledger.AlreadyProcessed a -> "already_processed";
                case Ledger.InsufficientFunds i -> "insufficient_funds";
            };
            return Response.json(200, "{\"result\":\"" + kind + "\"}");
        } catch (IllegalArgumentException e) {
            return Response.json(400, "{\"error\":\"" + Json.esc(e.getMessage()) + "\"}");
        }
    }

    private static Response notifications(HttpExchange ex) throws Exception {
        StringBuilder b = new StringBuilder("[");
        try (Connection c = Notifications.openForRead(); var st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT event_key, message, created_at FROM notifications ORDER BY created_at DESC LIMIT 20")) {
            boolean first = true;
            while (rs.next()) {
                if (!first) b.append(',');
                first = false;
                b.append("{\"key\":\"").append(rs.getString(1))
                 .append("\",\"message\":\"").append(Json.esc(rs.getString(2)))
                 .append("\",\"at\":\"").append(rs.getTimestamp(3).toInstant()).append("\"}");
            }
        }
        return Response.json(200, b.append(']').toString());
    }

    // ------------------------------------------------------------------ x-ray

    private static Response xraySummary(HttpExchange ex) throws Exception {
        long accounts, transactions, entries, outboxPending, outboxPublished;
        try (Connection c = Db.open(); var st = c.createStatement()) {
            accounts = one(st.executeQuery("SELECT COUNT(*) FROM accounts"));
            transactions = one(st.executeQuery("SELECT COUNT(*) FROM transactions"));
            entries = one(st.executeQuery("SELECT COUNT(*) FROM entries"));
            outboxPending = one(st.executeQuery("SELECT COUNT(*) FROM outbox WHERE published_at IS NULL"));
            outboxPublished = one(st.executeQuery("SELECT COUNT(*) FROM outbox WHERE published_at IS NOT NULL"));
        }
        int notifCount = Notifications.count();
        int sumZeroViolations = Ledger.sumZeroViolations().size();
        int drifted = Ledger.driftedAccounts().size();
        return Response.json(200,
                "{\"accounts\":" + accounts + ",\"transactions\":" + transactions +
                ",\"entries\":" + entries + ",\"outboxPending\":" + outboxPending +
                ",\"outboxPublished\":" + outboxPublished + ",\"notifications\":" + notifCount +
                ",\"sumZeroViolations\":" + sumZeroViolations + ",\"driftedAccounts\":" + drifted + "}");
    }

    private static Response xrayEntries(HttpExchange ex) throws Exception {
        StringBuilder b = new StringBuilder("[");
        try (Connection c = Db.open(); var st = c.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT e.tx_id, a.owner, e.amount, e.created_at
                     FROM entries e JOIN accounts a ON a.id = e.account_id
                     ORDER BY e.id DESC LIMIT 30""")) {
            boolean first = true;
            while (rs.next()) {
                if (!first) b.append(',');
                first = false;
                b.append("{\"tx\":\"").append(rs.getString(1))
                 .append("\",\"owner\":\"").append(Json.esc(rs.getString(2)))
                 .append("\",\"amount\":\"").append(rs.getBigDecimal(3).toPlainString())
                 .append("\",\"at\":\"").append(rs.getTimestamp(4).toInstant()).append("\"}");
            }
        }
        return Response.json(200, b.append(']').toString());
    }

    private static Response xrayOutbox(HttpExchange ex) throws Exception {
        StringBuilder b = new StringBuilder("[");
        try (Connection c = Db.open(); var st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, key, payload, published_at FROM outbox ORDER BY id DESC LIMIT 20")) {
            boolean first = true;
            while (rs.next()) {
                if (!first) b.append(',');
                first = false;
                b.append("{\"id\":").append(rs.getLong(1))
                 .append(",\"key\":\"").append(rs.getString(2))
                 .append("\",\"payload\":\"").append(Json.esc(rs.getString(3)))
                 .append("\",\"published\":").append(rs.getTimestamp(4) != null).append("}");
            }
        }
        return Response.json(200, b.append(']').toString());
    }

    // ------------------------------------------------------------------ static

    private static Response staticFile(HttpExchange ex) throws Exception {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        if (path.contains("..")) return Response.json(404, "{}");
        try (InputStream in = HttpApi.class.getResourceAsStream("/web" + path)) {
            if (in == null) return Response.json(404, "{\"error\":\"not found\"}");
            String type = path.endsWith(".html") ? "text/html; charset=utf-8"
                    : path.endsWith(".js") ? "application/javascript"
                    : path.endsWith(".css") ? "text/css" : "application/octet-stream";
            return new Response(200, type, in.readAllBytes());
        }
    }

    // ------------------------------------------------------------------ plumbing

    private record Response(int status, String contentType, byte[] body) {
        static Response json(int status, String json) {
            return new Response(status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
        }
    }

    @FunctionalInterface
    private interface Handler {
        Response run(HttpExchange ex) throws Exception;
    }

    private static void handle(HttpExchange ex, Handler h) throws IOException {
        Response r;
        try {
            r = h.run(ex);
        } catch (Exception e) {
            r = Response.json(500, "{\"error\":\"" + Json.esc(String.valueOf(e.getMessage())) + "\"}");
        }
        ex.getResponseHeaders().set("Content-Type", r.contentType());
        ex.sendResponseHeaders(r.status(), r.body().length);
        ex.getResponseBody().write(r.body());
        ex.close();
    }

    private static long one(ResultSet rs) throws Exception {
        rs.next();
        return rs.getLong(1);
    }
}
