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
        server.createContext("/api/relocate", ex -> handle(ex, HttpApi::relocate));
        server.createContext("/api/statement", ex -> handle(ex, HttpApi::statement));
        server.createContext("/api/notifications", ex -> handle(ex, HttpApi::notifications));
        server.createContext("/api/xray/summary", ex -> handle(ex, HttpApi::xraySummary));
        server.createContext("/api/xray/events", ex -> handle(ex, HttpApi::xrayEvents));
        server.createContext("/api/xray/trace", ex -> handle(ex, HttpApi::xrayTrace));
        server.createContext("/api/xray/entries", ex -> handle(ex, HttpApi::xrayEntries));
        server.createContext("/api/xray/outbox", ex -> handle(ex, HttpApi::xrayOutbox));
        server.createContext("/", ex -> handle(ex, HttpApi::staticFile));

        server.start();
        return server;
    }

    // ------------------------------------------------------------------ app

    /** Customers only, each from its HOME region — reads route through the
     *  directory exactly like writes do. After a relocation the emptied
     *  account still exists on the old region as an archive; it is not the
     *  customer's account anymore, so it does not appear here. */
    private static Response accounts(HttpExchange ex) throws Exception {
        StringBuilder b = new StringBuilder("[");
        boolean first = true;
        for (Shard s : Shards.all()) {
            try (Connection c = s.open(); var st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT id, owner, kind, balance FROM accounts WHERE kind = 'customer' ORDER BY id")) {
                while (rs.next()) {
                    long id = rs.getLong(1);
                    boolean home;
                    try {
                        home = Shards.forCustomer(id).index == s.index;
                    } catch (RuntimeException e) {
                        home = true;   // mid-relocation or no directory: show as-is
                    }
                    if (!home) continue;   // an archive on a former region
                    if (!first) b.append(',');
                    first = false;
                    b.append("{\"id\":").append(id)
                     .append(",\"owner\":\"").append(Json.esc(rs.getString(2)))
                     .append("\",\"kind\":\"").append(rs.getString(3))
                     .append("\",\"balance\":\"").append(rs.getBigDecimal(4).toPlainString())
                     .append("\",\"shard\":").append(s.index)
                     .append(",\"region\":\"").append(Shards.regionName(s.index)).append("\"}");
                }
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
            // THE ROUTER: same shard -> the stage-1 ACID transfer, unchanged.
            // Different shards -> the saga departs; Kafka settles the rest.
            Shards.Plan plan = Shards.plan(Long.parseLong(from), Long.parseLong(to));
            Ledger.TransferResult result = plan.crossShard()
                    ? plan.source().depart(UUID.fromString(txId),
                            Long.parseLong(from), Long.parseLong(to), new BigDecimal(amount))
                    : plan.source().transferLocal(UUID.fromString(txId),
                            Long.parseLong(from), Long.parseLong(to), new BigDecimal(amount));
            String kind = switch (result) {
                case Ledger.Ok ok -> "ok";
                case Ledger.AlreadyProcessed a -> "already_processed";
                case Ledger.InsufficientFunds i -> "insufficient_funds";
                case Ledger.NoSuchAccount n -> "no_such_account";
            };
            return Response.json(200, "{\"result\":\"" + kind +
                    "\",\"crossShard\":" + plan.crossShard() + "}");
        } catch (Directory.CustomerMoving e) {
            // not a failure — an instruction. The write-pause of a relocation.
            return Response.json(409, "{\"result\":\"relocating\",\"error\":\"" + Json.esc(e.getMessage()) + "\"}");
        } catch (IllegalArgumentException e) {
            return Response.json(400, "{\"error\":\"" + Json.esc(e.getMessage()) + "\"}");
        }
    }

    /** STAGE 6: move a customer to another region — the balance travels
     *  through the standard saga, then the directory pointer flips. */
    private static Response relocate(HttpExchange ex) throws Exception {
        if (!"POST".equals(ex.getRequestMethod())) return Response.json(405, "{\"error\":\"POST only\"}");
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String customer = Json.num(body, "customer");
        String to = Json.num(body, "to");
        if (customer == null || to == null)
            return Response.json(400, "{\"error\":\"need customer, to\"}");
        try {
            Relocation.relocate(Long.parseLong(customer), Integer.parseInt(to));
            return Response.json(200, "{\"result\":\"ok\",\"region\":\"" +
                    Shards.regionName(Integer.parseInt(to)) + "\"}");
        } catch (Directory.CustomerMoving e) {
            return Response.json(409, "{\"result\":\"relocating\",\"error\":\"" + Json.esc(e.getMessage()) + "\"}");
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

    /** The Revolut-style statement: the customer's raw ledger entries turned
     *  into a human story. Each row = one entry on THEIR account, joined to
     *  the tx's OTHER entry for the counterparty, plus a running balance
     *  (a window SUM over the very entries the balance is a cache of).
     *  Saga legs name in_transit locally — the human counterparty comes from
     *  the departed event's payload (on the destination side that means one
     *  lookup on the other region: a read-model shortcut; a real fleet
     *  projects statements from the Kafka events instead). */
    private static Response statement(HttpExchange ex) throws Exception {
        String q = ex.getRequestURI().getQuery();
        String cust = null;
        if (q != null) for (String p : q.split("&")) if (p.startsWith("customer=")) cust = p.substring(9);
        if (cust == null) return Response.json(400, "{\"error\":\"need ?customer=id\"}");
        long id = Long.parseLong(cust);
        Shard home = Shards.forCustomer(id);

        StringBuilder b = new StringBuilder("[");
        try (Connection c = home.open();
             var ps = c.prepareStatement("""
                     SELECT e.tx_id, e.amount, e.created_at, t.kind,
                            oa.owner AS other_owner, oa.kind AS other_kind,
                            SUM(e.amount) OVER (ORDER BY e.id) AS balance_after
                     FROM entries e
                     JOIN transactions t ON t.id = e.tx_id
                     LEFT JOIN entries o  ON o.tx_id = e.tx_id AND o.id <> e.id
                     LEFT JOIN accounts oa ON oa.id = o.account_id
                     WHERE e.account_id = ?
                     ORDER BY e.id DESC
                     LIMIT 40""")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    UUID tx = rs.getObject(1, UUID.class);
                    var amount = rs.getBigDecimal(2);
                    var at = rs.getTimestamp(3).toInstant();
                    String kind = rs.getString(4);
                    String otherOwner = rs.getString(5);
                    String otherKind = rs.getString(6);
                    var after = rs.getBigDecimal(7);
                    boolean in = amount.signum() > 0;

                    String label, tag;
                    boolean cross = false;
                    switch (kind) {
                        case "depart" -> {
                            cross = true;
                            String to = outboxField(c, tx, "to");
                            if (to != null && Long.parseLong(to) == id) { label = "Relocation"; tag = "relocation"; }
                            else { label = ownerName(to); tag = "sent"; }
                        }
                        case "arrive" -> {
                            cross = true;
                            String from = departedFieldElsewhere(home, tx, "from");
                            if (from != null && Long.parseLong(from) == id) { label = "Relocation"; tag = "relocation"; }
                            else { label = ownerName(from); tag = "received"; }
                        }
                        case "refund" -> { cross = true; label = "Refund"; tag = "refund"; }
                        default -> {
                            if ("external".equals(otherKind)) {
                                label = in ? "Money added" : (otherOwner == null ? "Payment" : otherOwner);
                                tag = in ? "added" : "sent";
                            } else {
                                label = otherOwner == null ? "Transfer" : otherOwner;
                                tag = in ? "received" : "sent";
                            }
                        }
                    }
                    if (!first) b.append(',');
                    first = false;
                    b.append("{\"tx\":\"").append(tx)
                     .append("\",\"at\":\"").append(at)
                     .append("\",\"amount\":\"").append(amount.toPlainString())
                     .append("\",\"after\":\"").append(after.toPlainString())
                     .append("\",\"label\":\"").append(Json.esc(label))
                     .append("\",\"tag\":\"").append(tag)
                     .append("\",\"in\":").append(in)
                     .append(",\"cross\":").append(cross).append('}');
                }
            }
        }
        return Response.json(200, b.append(']').toString());
    }

    /** a field of the departed event in THIS shard's outbox (the depart leg) */
    private static String outboxField(Connection c, UUID tx, String field) throws Exception {
        try (var ps = c.prepareStatement("SELECT payload FROM outbox WHERE key = ?")) {
            ps.setString(1, "departed:" + tx);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Json.num(rs.getString(1), field) : null;
            }
        }
    }

    /** the arrival leg has no local event — ask the other regions' outboxes */
    private static String departedFieldElsewhere(Shard home, UUID tx, String field) throws Exception {
        for (Shard s : Shards.all()) {
            if (s == home) continue;
            try (Connection c = s.open()) {
                String v = outboxField(c, tx, field);
                if (v != null) return v;
            }
        }
        return null;
    }

    private static String ownerName(String idStr) {
        if (idStr == null) return "Transfer";
        try {
            return Directory.owner(Long.parseLong(idStr));
        } catch (Exception e) {
            return "user " + idStr;
        }
    }

    // ------------------------------------------------------------------ x-ray

    private static Response xraySummary(HttpExchange ex) throws Exception {
        long tAccounts = 0, tTransactions = 0, tEntries = 0, tPending = 0, tPublished = 0;
        int tViolations = 0, tDrifted = 0;
        StringBuilder shardsJson = new StringBuilder("[");
        for (Shard s : Shards.all()) {
            long accounts, transactions, entries, pending, published;
            int violations, drifted;
            String inTransit;
            try (Connection c = s.open(); var st = c.createStatement()) {
                accounts = one(st.executeQuery("SELECT COUNT(*) FROM accounts"));
                transactions = one(st.executeQuery("SELECT COUNT(*) FROM transactions"));
                entries = one(st.executeQuery("SELECT COUNT(*) FROM entries"));
                pending = one(st.executeQuery("SELECT COUNT(*) FROM outbox WHERE published_at IS NULL"));
                published = one(st.executeQuery("SELECT COUNT(*) FROM outbox WHERE published_at IS NOT NULL"));
                violations = Ledger.sumZeroViolationsOn(c).size();
                drifted = Ledger.driftedAccountsOn(c).size();
                inTransit = Ledger.cachedBalanceOn(c, Shard.IN_TRANSIT).toPlainString();
            }
            tAccounts += accounts; tTransactions += transactions; tEntries += entries;
            tPending += pending; tPublished += published;
            tViolations += violations; tDrifted += drifted;
            if (s.index > 0) shardsJson.append(',');
            shardsJson.append("{\"shard\":").append(s.index)
                    .append(",\"region\":\"").append(Shards.regionName(s.index))
                    .append("\",\"accounts\":").append(accounts)
                    .append(",\"transactions\":").append(transactions)
                    .append(",\"entries\":").append(entries)
                    .append(",\"outboxPending\":").append(pending)
                    .append(",\"outboxPublished\":").append(published)
                    .append(",\"sumZeroViolations\":").append(violations)
                    .append(",\"driftedAccounts\":").append(drifted)
                    .append(",\"inTransit\":\"").append(inTransit)
                    .append("\",\"poolBusy\":").append(s.pool().borrowedCount())
                    .append(",\"poolSize\":").append(s.pool().size()).append('}');
        }
        shardsJson.append(']');
        return Response.json(200,
                "{\"accounts\":" + tAccounts + ",\"transactions\":" + tTransactions +
                ",\"entries\":" + tEntries + ",\"outboxPending\":" + tPending +
                ",\"outboxPublished\":" + tPublished + ",\"notifications\":" + Notifications.count() +
                ",\"sumZeroViolations\":" + tViolations + ",\"driftedAccounts\":" + tDrifted +
                ",\"inFlight\":\"" + Shards.inFlight().toPlainString() +
                "\",\"shards\":" + shardsJson + "}");
    }

    /** The live activity stream: every commit, publish and delivery across
     *  the whole bank, merged and timestamped — the observability the tables
     *  already contained, finally asked for. Powers the map animations. */
    private static Response xrayEvents(HttpExchange ex) throws Exception {
        record Ev(java.time.Instant ts, String type, int shard, String region,
                  String tx, String payer, String payee, String amount, String payload) {}
        java.util.List<Ev> evs = new java.util.ArrayList<>();

        for (Shard s : Shards.all()) {
            String region = Shards.regionName(s.index);
            try (Connection c = s.open()) {
                // committed transactions with their two legs resolved to names
                try (var ps = c.prepareStatement("""
                        SELECT t.id, t.kind, t.created_at,
                               max(CASE WHEN e.amount < 0 THEN a.owner END) AS payer,
                               max(CASE WHEN e.amount > 0 THEN a.owner END) AS payee,
                               max(CASE WHEN e.amount > 0 THEN e.amount END) AS amount
                        FROM transactions t
                        JOIN entries e ON e.tx_id = t.id
                        JOIN accounts a ON a.id = e.account_id
                        GROUP BY t.id, t.kind, t.created_at
                        ORDER BY t.created_at DESC LIMIT 15""");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String kind = rs.getString(2);
                        String type = "transfer".equals(kind) ? "transfer_local" : kind;
                        evs.add(new Ev(rs.getTimestamp(3).toInstant(), type, s.index, region,
                                rs.getString(1), rs.getString(4), rs.getString(5),
                                rs.getBigDecimal(6) == null ? null : rs.getBigDecimal(6).toPlainString(), null));
                    }
                }
                // relay publishes (the Kafka moment)
                try (var ps = c.prepareStatement(
                        "SELECT key, payload, published_at FROM outbox WHERE published_at IS NOT NULL ORDER BY published_at DESC LIMIT 10");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String key = rs.getString(1);
                        String tx = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;
                        evs.add(new Ev(rs.getTimestamp(3).toInstant(), "published", s.index, region,
                                tx, null, null, null, rs.getString(2)));
                    }
                }
            }
        }
        try (Connection c = Notifications.openForRead();
             var ps = c.prepareStatement("SELECT event_key, created_at FROM notifications ORDER BY created_at DESC LIMIT 10");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String key = rs.getString(1);
                String tx = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;
                evs.add(new Ev(rs.getTimestamp(2).toInstant(), "notify", -1, "notifications", tx, null, null, null, null));
            }
        }

        evs.sort((a, b2) -> b2.ts().compareTo(a.ts()));
        StringBuilder b = new StringBuilder("[");
        int n = 0;
        for (Ev e : evs) {
            if (n++ == 30) break;
            if (n > 1) b.append(',');
            b.append("{\"ts\":\"").append(e.ts())
             .append("\",\"type\":\"").append(e.type())
             .append("\",\"shard\":").append(e.shard())
             .append(",\"region\":\"").append(e.region())
             .append("\",\"tx\":\"").append(e.tx()).append('"');
            if (e.payer() != null) b.append(",\"payer\":\"").append(Json.esc(e.payer())).append('"');
            if (e.payee() != null) b.append(",\"payee\":\"").append(Json.esc(e.payee())).append('"');
            if (e.amount() != null) b.append(",\"amount\":\"").append(e.amount()).append('"');
            if (e.payload() != null) b.append(",\"payload\":\"").append(Json.esc(e.payload())).append('"');
            b.append('}');
        }
        return Response.json(200, b.append(']').toString());
    }

    /** One transaction's whole journey, assembled from the timestamps the
     *  system already wrote: commits on each region, the relay's publish,
     *  the notification's insert. Distributed tracing from first principles
     *  — no agent injected anything; the ledger IS the trace. */
    private static Response xrayTrace(HttpExchange ex) throws Exception {
        String q = ex.getRequestURI().getQuery();
        String tx = null;
        if (q != null) for (String p : q.split("&")) if (p.startsWith("tx=")) tx = p.substring(3);
        if (tx == null) return Response.json(400, "{\"error\":\"need ?tx=uuid\"}");
        UUID id = UUID.fromString(tx);
        UUID refundId = UUID.nameUUIDFromBytes(("refund:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        record Step(java.time.Instant ts, String step, String region, String detail) {}
        java.util.List<Step> steps = new java.util.ArrayList<>();
        String payer = null, payee = null, amount = null;

        for (Shard s : Shards.all()) {
            String region = Shards.regionName(s.index);
            try (Connection c = s.open()) {
                try (var ps = c.prepareStatement("""
                        SELECT t.id, t.kind, t.created_at,
                               max(CASE WHEN e.amount < 0 THEN a.owner END),
                               max(CASE WHEN e.amount > 0 THEN a.owner END),
                               max(CASE WHEN e.amount > 0 THEN e.amount END)
                        FROM transactions t
                        JOIN entries e ON e.tx_id = t.id
                        JOIN accounts a ON a.id = e.account_id
                        WHERE t.id = ? OR t.id = ?
                        GROUP BY t.id, t.kind, t.created_at""")) {
                    ps.setObject(1, id);
                    ps.setObject(2, refundId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String kind = rs.getString(2);
                            String label = switch (kind) {
                                case "transfer" -> "committed (local ACID)";
                                case "depart" -> "departed — money into the pipe";
                                case "arrive" -> "arrived — money out of the pipe";
                                case "refund" -> "refunded — the compensating transaction";
                                default -> kind;
                            };
                            steps.add(new Step(rs.getTimestamp(3).toInstant(), kind, region, label));
                            if (payer == null || "depart".equals(kind) || "transfer".equals(kind)) {
                                payer = rs.getString(4);
                                if (rs.getString(5) != null && !"in_transit".equals(rs.getString(5))) payee = rs.getString(5);
                                if (rs.getBigDecimal(6) != null) amount = rs.getBigDecimal(6).toPlainString();
                            }
                            if ("arrive".equals(kind) && rs.getString(5) != null) payee = rs.getString(5);
                        }
                    }
                }
                try (var ps = c.prepareStatement(
                        "SELECT key, created_at, published_at FROM outbox WHERE key = ? OR key = ? OR key = ?")) {
                    ps.setString(1, tx);
                    ps.setString(2, "departed:" + tx);
                    ps.setString(3, "bounced:" + tx);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            if (rs.getTimestamp(3) != null) {
                                steps.add(new Step(rs.getTimestamp(3).toInstant(), "published", region,
                                        "relay -> Kafka (broker acked, then marked)"));
                            }
                        }
                    }
                }
            }
        }
        try (Connection c = Notifications.openForRead();
             var ps = c.prepareStatement(
                     "SELECT event_key, created_at FROM notifications WHERE event_key = ? OR event_key = ? OR event_key = ?")) {
            ps.setString(1, tx);
            ps.setString(2, "departed:" + tx);
            ps.setString(3, "bounced:" + tx);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    steps.add(new Step(rs.getTimestamp(2).toInstant(), "notify", "notifications",
                            "notification stored (idempotent consumer)"));
                }
            }
        }

        steps.sort(java.util.Comparator.comparing(Step::ts));
        StringBuilder b = new StringBuilder("{\"tx\":\"").append(tx).append('"');
        if (payer != null) b.append(",\"payer\":\"").append(Json.esc(payer)).append('"');
        if (payee != null) b.append(",\"payee\":\"").append(Json.esc(payee)).append('"');
        if (amount != null) b.append(",\"amount\":\"").append(amount).append('"');
        b.append(",\"steps\":[");
        boolean first = true;
        for (Step st : steps) {
            if (!first) b.append(',');
            first = false;
            b.append("{\"ts\":\"").append(st.ts())
             .append("\",\"step\":\"").append(st.step())
             .append("\",\"region\":\"").append(st.region())
             .append("\",\"detail\":\"").append(Json.esc(st.detail())).append("\"}");
        }
        return Response.json(200, b.append("]}").toString());
    }

    private static Response xrayEntries(HttpExchange ex) throws Exception {
        record Row(String tx, String owner, String amount, java.time.Instant at, int shard) {}
        java.util.List<Row> rows = new java.util.ArrayList<>();
        for (Shard s : Shards.all()) {
            try (Connection c = s.open(); var st = c.createStatement();
                 ResultSet rs = st.executeQuery("""
                         SELECT e.tx_id, a.owner, e.amount, e.created_at
                         FROM entries e JOIN accounts a ON a.id = e.account_id
                         ORDER BY e.id DESC LIMIT 30""")) {
                while (rs.next()) {
                    rows.add(new Row(rs.getString(1), rs.getString(2),
                            rs.getBigDecimal(3).toPlainString(), rs.getTimestamp(4).toInstant(), s.index));
                }
            }
        }
        rows.sort((a, b2) -> b2.at().compareTo(a.at()));
        StringBuilder b = new StringBuilder("[");
        int n = 0;
        for (Row r : rows) {
            if (n++ == 30) break;
            if (n > 1) b.append(',');
            b.append("{\"tx\":\"").append(r.tx())
             .append("\",\"owner\":\"").append(Json.esc(r.owner()))
             .append("\",\"amount\":\"").append(r.amount())
             .append("\",\"at\":\"").append(r.at())
             .append("\",\"shard\":").append(r.shard()).append('}');
        }
        return Response.json(200, b.append(']').toString());
    }

    private static Response xrayOutbox(HttpExchange ex) throws Exception {
        StringBuilder b = new StringBuilder("[");
        boolean first = true;
        for (Shard s : Shards.all()) {
            try (Connection c = s.open(); var st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT id, key, payload, published_at FROM outbox ORDER BY id DESC LIMIT 10")) {
                while (rs.next()) {
                    if (!first) b.append(',');
                    first = false;
                    b.append("{\"id\":").append(rs.getLong(1))
                     .append(",\"key\":\"").append(rs.getString(2))
                     .append("\",\"payload\":\"").append(Json.esc(rs.getString(3)))
                     .append("\",\"published\":").append(rs.getTimestamp(4) != null)
                     .append(",\"shard\":").append(s.index).append('}');
                }
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
