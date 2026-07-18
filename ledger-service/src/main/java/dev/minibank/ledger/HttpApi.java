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
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * STAGE 3 · THE BANK GETS A FACE. Raw JDK HttpServer, no framework.
 *
 * THE JAVA 21 POINT: the server runs one VIRTUAL THREAD per request.
 * Virtual threads make blocking code cheap · a thread waiting on JDBC or
 * Kafka costs almost nothing, so we write simple blocking handlers and
 * still scale. This is the modern answer to "how do you handle 10K
 * concurrent requests in Java" · not callback spaghetti, not reactive
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
        server.createContext("/api/portfolio", ex -> handle(ex, HttpApi::portfolio));
        server.createContext("/api/trade", ex -> handle(ex, HttpApi::trade));
        server.createContext("/api/mortgage", ex -> handle(ex, HttpApi::mortgageApply));
        server.createContext("/api/card", ex -> handle(ex, HttpApi::cardOps));
        // THE ISSUER FACE. A different route family from /api/* on purpose:
        // /api is the cardholder's own bank, /issuer is what an acquirer may
        // reach, and keeping them apart is what stops a merchant's processor
        // quietly acquiring the cardholder's powers.
        server.createContext("/issuer/v1/instruments", ex -> handle(ex, HttpApi::issuerInstrument));
        server.createContext("/issuer/v1/authorizations", ex -> handle(ex, HttpApi::issuerAuthorize));
        server.createContext("/issuer/v1/clearing", ex -> handle(ex, HttpApi::issuerClearing));
        server.createContext("/api/signup", ex -> handle(ex, HttpApi::signup));
        server.createContext("/api/support", ex -> handle(ex, HttpApi::support));
        server.createContext("/api/prices/history", ex -> handle(ex, HttpApi::priceHistory));
        // Prometheus scrapes this · plain text exposition, no client library
        server.createContext("/metrics", ex -> handle(ex, e -> Response.text(200, Metrics.scrape())));
        server.createContext("/api/explorer/catalog", ex -> handle(ex, HttpApi::explorerCatalog));
        server.createContext("/api/explorer/run", ex -> handle(ex, HttpApi::explorerRun));
        server.createContext("/api/kafka/console", ex -> handle(ex, HttpApi::kafkaConsole));
        server.createContext("/api/notifications", ex -> handle(ex, HttpApi::notifications));
        server.createContext("/api/xray/summary", ex -> handle(ex, HttpApi::xraySummary));
        server.createContext("/api/xray/events", ex -> handle(ex, HttpApi::xrayEvents));
        server.createContext("/api/xray/inspect", ex -> handle(ex, HttpApi::xrayInspect));
        server.createContext("/api/xray/trace", ex -> handle(ex, HttpApi::xrayTrace));
        server.createContext("/api/xray/entries", ex -> handle(ex, HttpApi::xrayEntries));
        server.createContext("/api/xray/outbox", ex -> handle(ex, HttpApi::xrayOutbox));
        server.createContext("/", ex -> handle(ex, HttpApi::staticFile));

        server.start();
        return server;
    }

    // ------------------------------------------------------------------ app

    /** Customers only, each from its HOME region · reads route through the
     *  directory exactly like writes do. After a relocation the emptied
     *  account still exists on the old region as an archive; it is not the
     *  customer's account anymore, so it does not appear here. */
    private static Response accounts(HttpExchange ex) throws Exception {
        StringBuilder b = new StringBuilder("[");
        boolean first = true;
        for (Shard s : Shards.all()) {
            try (Connection c = s.open(); var st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         // id < 100: product accounts (savings/card/assets/loan
                         // at fixed offsets) are the customer's, not customers
                         "SELECT id, owner, kind, balance FROM accounts WHERE kind = 'customer' AND id < 100 ORDER BY id")) {
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
                     .append("\",\"balance\":\"").append(plain(rs.getBigDecimal(4)))
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
        // ONLY `from` · `from` is the payer, and the payer is the only party
        // whose identity the token can possibly speak for. Overriding `to` as
        // well would turn every authenticated payment into a self-transfer,
        // silently and with a 200.
        String from = caller(Json.num(body, "from"));
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
            // Counted by what actually happened, not by what was attempted. This
            // used to increment saga_depart / transfer_local unconditionally, so
            // a transfer refused for insufficient funds was published to the
            // dashboard as a completed money movement. A rejection is a real
            // event worth counting, but it is a DIFFERENT event, and a graph
            // that cannot tell them apart is worse than no graph.
            Metrics.inc("minibank_ledger_events_total", "kind=\"" + switch (result) {
                case Ledger.Ok ok -> plan.crossShard() ? "saga_depart" : "transfer_local";
                case Ledger.AlreadyProcessed a -> "replayed";
                case Ledger.InsufficientFunds i -> "declined_funds";
                case Ledger.NoSuchAccount n -> "declined_no_account";
            } + "\"");
            return Response.json(200, "{\"result\":\"" + kind +
                    "\",\"crossShard\":" + plan.crossShard() + "}");
        } catch (Directory.CustomerMoving e) {
            // not a failure · an instruction. The write-pause of a relocation.
            return Response.json(409, "{\"result\":\"relocating\",\"error\":\"" + Json.esc(e.getMessage()) + "\"}");
        } catch (IllegalArgumentException e) {
            return Response.json(400, "{\"error\":\"" + Json.esc(e.getMessage()) + "\"}");
        }
    }

    /** STAGE 6: move a customer to another region · the balance travels
     *  through the standard saga, then the directory pointer flips. */
    private static Response relocate(HttpExchange ex) throws Exception {
        if (!"POST".equals(ex.getRequestMethod())) return Response.json(405, "{\"error\":\"POST only\"}");
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String customer = caller(Json.num(body, "customer"));
        String to = Json.num(body, "to");   // a REGION, not an account · not identity
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
     *  Saga legs name in_transit locally · the human counterparty comes from
     *  the departed event's payload (on the destination side that means one
     *  lookup on the other region: a read-model shortcut; a real fleet
     *  projects statements from the Kafka events instead). */
    /** The statement page's one query. Package-private so the test can EXPLAIN
     *  exactly what ships: its plan must stay a bounded index scan and must
     *  never grow a WindowAgg over the account's whole history again. */
    static final String STATEMENT_SQL = """
            SELECT e.tx_id, e.amount, e.created_at, t.kind,
                   o.owner AS other_owner, o.kind AS other_kind
            FROM entries e
            JOIN transactions t ON t.id = e.tx_id
            LEFT JOIN LATERAL (
                SELECT oa.owner, oa.kind FROM entries o2
                JOIN accounts oa ON oa.id = o2.account_id
                WHERE o2.tx_id = e.tx_id AND o2.id <> e.id
                ORDER BY o2.id LIMIT 1) o ON true
            WHERE e.account_id = ?
            ORDER BY e.id DESC
            LIMIT 40""";

    private static Response statement(HttpExchange ex) throws Exception {
        String q = ex.getRequestURI().getQuery();
        String cust = null;
        if (q != null) for (String p : q.split("&")) if (p.startsWith("customer=")) cust = p.substring(9);
        cust = caller(cust);   // the token's customer, or the parameter, or neither
        if (cust == null) return Response.json(400, "{\"error\":\"need ?customer=id\"}");
        long id = Long.parseLong(cust);
        Shard home = Shards.forCustomer(id);

        StringBuilder b = new StringBuilder("[");
        try (Connection c = home.open()) {
            /* THE RUNNING BALANCE, WITHOUT READING THE WHOLE LEDGER.
               This used to be SUM(e.amount) OVER (ORDER BY e.id) · a window
               over EVERY entry the account ever had, computed in full before
               ORDER BY ... LIMIT 40 could throw almost all of it away. The
               page shows 40 rows; the query grew with the account's whole
               history, and the customers with the most history waited the
               longest for it.
               The bank already maintains the answer: the cached balance,
               reconciled against the entries by the drift audit on every
               refresh. So anchor on it and walk BACKWARDS through the 40 rows
               we actually fetched · balance_after(row) = balance_after(newer)
               − amount(newer). Same numbers, O(40) instead of O(history), and
               it reuses the projection rather than recomputing the truth. */
            BigDecimal running = Ledger.cachedBalanceOn(c, id);
            try (var ps = c.prepareStatement(STATEMENT_SQL)) {
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
                    // newest row first: its "after" IS the current balance
                    var after = running;
                    running = running.subtract(amount);
                    boolean in = amount.signum() > 0;

                    String label, tag;
                    boolean cross = false, pending = false;
                    switch (kind) {
                        case "depart" -> {
                            cross = true;
                            String to = outboxField(c, tx, "to");
                            if (to != null && Long.parseLong(to) == id) { label = "Relocation"; tag = "relocation"; }
                            else { label = ownerName(to); tag = "sent"; }
                            // Revolut-style honesty: a departed payment is
                            // PENDING until its arrival (or refund) commits.
                            pending = isStillInFlight(c, home, tx, to);
                        }
                        case "arrive" -> {
                            cross = true;
                            String from = departedFieldElsewhere(home, tx, "from");
                            if (from != null && Long.parseLong(from) == id) { label = "Relocation"; tag = "relocation"; }
                            else { label = ownerName(from); tag = "received"; }
                        }
                        case "refund" -> { cross = true; label = "Refund"; tag = "refund"; }
                        case "mortgage" -> { label = "Loan"; tag = "loan"; }
                        default -> {
                            if (kind.startsWith("trade:")) {
                                String[] parts = kind.split(":");
                                label = "btc".equals(parts[1]) ? "Bitcoin" : "Apple stock";
                                tag = parts[2];   // buy | sell
                            } else if ("external".equals(otherKind)) {
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
                     .append("\",\"amount\":\"").append(plain(amount))
                     .append("\",\"after\":\"").append(plain(after))
                     .append("\",\"label\":\"").append(Json.esc(label))
                     .append("\",\"tag\":\"").append(tag)
                     .append("\",\"in\":").append(in)
                     .append(",\"cross\":").append(cross)
                     .append(",\"pending\":").append(pending).append('}');
                }
            }
            }
        }
        return Response.json(200, b.append(']').toString());
    }

    /** pending = departed, and neither the arrival (destination shard) nor a
     *  compensating refund (this shard) has committed yet. */
    private static boolean isStillInFlight(Connection homeConn, Shard home, UUID tx, String toStr) {
        try {
            UUID refundId = UUID.nameUUIDFromBytes(("refund:" + tx).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try (var ps = homeConn.prepareStatement("SELECT 1 FROM transactions WHERE id = ? AND kind = 'refund'")) {
                ps.setObject(1, refundId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return false;    // bounced and refunded: settled
                }
            }
            if (toStr == null) return false;
            Shard dest = Shards.forCustomer(Long.parseLong(toStr));
            if (dest == home) return false;
            try (Connection dc = dest.open();
                 var ps = dc.prepareStatement("SELECT 1 FROM transactions WHERE id = ? AND kind = 'arrive'")) {
                ps.setObject(1, tx);
                try (ResultSet rs = ps.executeQuery()) {
                    return !rs.next();
                }
            }
        } catch (Exception e) {
            return false;   // cannot check right now · don't alarm the user
        }
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

    /** the arrival leg has no local event · ask the other regions' outboxes */
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

    /** The system accounts, by their seeded owner names. */
    private static String systemName(long id) {
        if (id == Shard.WORLD) return "world";
        if (id == Shard.IN_TRANSIT) return "in transit";
        if (id == Shard.BROKER_EUR || id == Shard.BROKER_BTC || id == Shard.BROKER_AAPL) return "broker";
        if (id == Shard.CAFE) return "cafe";
        return null;
    }

    /** Product accounts live at fixed offsets from the customer id. */
    private static String productName(long offset) {
        if (offset == Products.SAVINGS) return "savings";
        if (offset == Products.BTC) return "bitcoin";
        if (offset == Products.AAPL) return "apple stock";
        if (offset == Products.CARD) return "card";
        if (offset == Products.LOAN) return "loan";
        if (offset == Products.HOLDS) return "card hold";
        return null;
    }

    /**
     * A human label for ANY account id · a customer, one of their product
     * accounts, or a system account. The primary key is plumbing: it never
     * belongs on screen ("user 112" means nothing · "oscar's savings" does).
     */
    private static String ownerName(String idStr) {
        if (idStr == null) return "Transfer";
        long id;
        try {
            id = Long.parseLong(idStr.trim());
        } catch (NumberFormatException e) {
            return "Transfer";
        }
        String sys = systemName(id);
        if (sys != null) return sys;
        long customer = id % 100, offset = id - customer;   // customers are id < 100
        String product = productName(offset);
        try {
            String owner = Directory.owner(customer);
            return product == null ? owner : owner + "'s " + product;
        } catch (Exception e) {
            return product == null ? "account " + id : product;
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
                inTransit = Ledger.cachedBalanceOn(c, Shard.IN_TRANSIT).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
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
            // feed the Prometheus gauges from the same numbers · one scrape
            // reads them lock-free, Grafana graphs them
            Metrics.gauge("minibank_pool_busy", "region=\"" + Shards.regionName(s.index) + "\"", s.pool().borrowedCount());
            Metrics.gauge("minibank_outbox_pending", "region=\"" + Shards.regionName(s.index) + "\"", pending);
        }
        shardsJson.append(']');
        Metrics.gauge("minibank_inflight_eur", "", Shards.inFlight().longValue());
        return Response.json(200,
                "{\"accounts\":" + tAccounts + ",\"transactions\":" + tTransactions +
                ",\"entries\":" + tEntries + ",\"outboxPending\":" + tPending +
                ",\"outboxPublished\":" + tPublished + ",\"notifications\":" + Notifications.count() +
                ",\"sumZeroViolations\":" + tViolations + ",\"driftedAccounts\":" + tDrifted +
                ",\"inFlight\":\"" + Shards.inFlight().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() +
                "\",\"cache\":" + cacheJson() +
                ",\"shards\":" + shardsJson + "}");
    }

    /** Redis + metrics snapshot for the X-ray Redis and Prometheus nodes. */
    private static String cacheJson() {
        String m = Metrics.uiJson();
        return m.substring(0, m.length() - 1) + ",\"redis\":" + Cache.enabled() + "}";
    }

    /** The live activity stream: every commit, publish and delivery across
     *  the whole bank, merged and timestamped · the observability the tables
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
                                rs.getBigDecimal(6) == null ? null : plain(rs.getBigDecimal(6)), null));
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

    /** Click a component on the map, see ITS actual database rows · the
     *  inspector serves each node's own truth: a shard's accounts/entries/
     *  outbox, the directory's routing table, the notifications table, the
     *  clearing accounts, the applier's commits. Nothing summarized:
     *  these are the rows. */
    private static Response xrayInspect(HttpExchange ex) throws Exception {
        String q = ex.getRequestURI().getQuery();
        String node = null;
        if (q != null) for (String p : q.split("&")) if (p.startsWith("node=")) node = p.substring(5);
        if (node == null) return Response.json(400, "{\"error\":\"need ?node=\"}");

        java.util.List<String> tables = new java.util.ArrayList<>();
        switch (node) {
            case "shard0", "shard1" -> {
                Shard s = Shards.s("shard1".equals(node) ? 1 : 0);
                try (Connection c = s.open()) {
                    tables.add(tableJson(c, "accounts · every account on this machine",
                            "SELECT id, owner, kind, currency, trim_scale(balance) AS balance FROM accounts ORDER BY id",
                            "id", "owner", "kind", "ccy", "balance"));
                    tables.add(tableJson(c, "entries · the double-entry truth (latest 8)",
                            """
                            SELECT substr(e.tx_id::text,1,8) AS tx, a.owner, trim_scale(e.amount) AS amount, to_char(e.created_at,'HH24:MI:SS') AS at
                            FROM entries e JOIN accounts a ON a.id = e.account_id ORDER BY e.id DESC LIMIT 8""",
                            "tx", "account", "amount", "at"));
                    tables.add(tableJson(c, "outbox · events born inside money commits (latest 5)",
                            """
                            SELECT substr(key,1,17) AS key, CASE WHEN published_at IS NULL THEN 'pending' ELSE 'published' END AS state,
                                   to_char(created_at,'HH24:MI:SS') AS at
                            FROM outbox ORDER BY id DESC LIMIT 5""",
                            "key", "state", "at"));
                }
            }
            case "api" -> {
                try (Connection c = Directory.openForRead()) {
                    tables.add(tableJson(c, "the routing directory · which region owns each customer",
                            "SELECT customer_id, owner, CASE WHEN shard = 0 THEN 'eu' ELSE 'uk' END AS region, moving FROM customers ORDER BY customer_id",
                            "customer", "owner", "region", "moving"));
                }
            }
            case "intransit" -> {
                for (Shard s : Shards.all()) {
                    try (Connection c = s.open()) {
                        tables.add(tableJson(c, "entries through " + Shards.regionName(s.index) + "'s clearing account (latest 5)",
                                """
                                SELECT substr(e.tx_id::text,1,8) AS tx, trim_scale(e.amount) AS amount, to_char(e.created_at,'HH24:MI:SS') AS at
                                FROM entries e WHERE e.account_id = 3 ORDER BY e.id DESC LIMIT 5""",
                                "tx", "amount", "at"));
                    }
                }
            }
            case "kafka" -> {
                for (Shard s : Shards.all()) {
                    try (Connection c = s.open()) {
                        tables.add(tableJson(c, "published from " + Shards.regionName(s.index) + "'s outbox (latest 5)",
                                """
                                SELECT substr(key,1,17) AS key, to_char(published_at,'HH24:MI:SS') AS published
                                FROM outbox WHERE published_at IS NOT NULL ORDER BY published_at DESC LIMIT 5""",
                                "key", "published"));
                    }
                }
            }
            case "applier" -> {
                for (Shard s : Shards.all()) {
                    try (Connection c = s.open()) {
                        tables.add(tableJson(c, "saga steps committed on " + Shards.regionName(s.index) + " (latest 6)",
                                """
                                SELECT substr(id::text,1,8) AS tx, kind, to_char(created_at,'HH24:MI:SS') AS at
                                FROM transactions WHERE kind IN ('depart','arrive','refund') ORDER BY created_at DESC LIMIT 6""",
                                "tx", "step", "at"));
                    }
                }
            }
            case "notif" -> {
                try (Connection c = Notifications.openForRead()) {
                    tables.add(tableJson(c, "notifications · its own database, fed only by Kafka (latest 6)",
                            "SELECT substr(event_key,1,17) AS key, to_char(created_at,'HH24:MI:SS') AS at FROM notifications ORDER BY created_at DESC LIMIT 6",
                            "event key", "stored at"));
                }
            }
            default -> { /* browser: nothing to inspect */ }
        }
        return Response.json(200, "{\"node\":\"" + Json.esc(node) + "\",\"tables\":[" + String.join(",", tables) + "]}");
    }

    /** run a query, emit {title, cols, rows} · the generic inspector table */
    private static String tableJson(Connection c, String title, String sql, String... cols) throws Exception {
        StringBuilder b = new StringBuilder("{\"title\":\"").append(Json.esc(title)).append("\",\"cols\":[");
        for (int i = 0; i < cols.length; i++) {
            if (i > 0) b.append(',');
            b.append('"').append(Json.esc(cols[i])).append('"');
        }
        b.append("],\"rows\":[");
        try (var st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int ncols = rs.getMetaData().getColumnCount();
            boolean firstRow = true;
            while (rs.next()) {
                if (!firstRow) b.append(',');
                firstRow = false;
                b.append('[');
                for (int i = 1; i <= ncols; i++) {
                    if (i > 1) b.append(',');
                    b.append('"').append(Json.esc(String.valueOf(rs.getObject(i)))).append('"');
                }
                b.append(']');
            }
        }
        return b.append("]}").toString();
    }

    /** One transaction's whole journey, assembled from the timestamps the
     *  system already wrote: commits on each region, the relay's publish,
     *  the notification's insert. Distributed tracing from first principles
     *  · no agent injected anything; the ledger IS the trace. */
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
                                case "depart" -> "departed · money into the pipe";
                                case "arrive" -> "arrived · money out of the pipe";
                                case "refund" -> "refunded · the compensating transaction";
                                default -> kind;
                            };
                            steps.add(new Step(rs.getTimestamp(3).toInstant(), kind, region, label));
                            if (payer == null || "depart".equals(kind) || "transfer".equals(kind)) {
                                payer = rs.getString(4);
                                if (rs.getString(5) != null && !"in_transit".equals(rs.getString(5))) payee = rs.getString(5);
                                if (rs.getBigDecimal(6) != null) amount = plain(rs.getBigDecimal(6));
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
        record Row(String tx, String owner, String amount, java.time.Instant at, int shard, String kind) {}
        java.util.List<Row> rows = new java.util.ArrayList<>();
        for (Shard s : Shards.all()) {
            try (Connection c = s.open(); var st = c.createStatement();
                 ResultSet rs = st.executeQuery("""
                         SELECT e.tx_id, a.owner, e.amount, e.created_at, t.kind
                         FROM entries e
                         JOIN accounts a ON a.id = e.account_id
                         JOIN transactions t ON t.id = e.tx_id
                         ORDER BY e.id DESC LIMIT 30""")) {
                while (rs.next()) {
                    rows.add(new Row(rs.getString(1), rs.getString(2),
                            plain(rs.getBigDecimal(3)), rs.getTimestamp(4).toInstant(), s.index, rs.getString(5)));
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
             .append("\",\"shard\":").append(r.shard())
             .append(",\"kind\":\"").append(r.kind()).append("\"}");
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
                    : path.endsWith(".css") ? "text/css"
                    : path.endsWith(".svg") ? "image/svg+xml"
                    : path.endsWith(".png") ? "image/png"
                    : path.endsWith(".json") ? "application/manifest+json" : "application/octet-stream";
            return new Response(200, type, in.readAllBytes());
        }
    }

    // ------------------------------------------------------------------ plumbing

    private record Response(int status, String contentType, byte[] body) {
        static Response json(int status, String json) {
            return new Response(status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
        }
        static Response text(int status, String txt) {
            return new Response(status, "text/plain; version=0.0.4; charset=utf-8", txt.getBytes(StandardCharsets.UTF_8));
        }
    }

    @FunctionalInterface
    private interface Handler {
        Response run(HttpExchange ex) throws Exception;
    }

    // the edge: a token bucket per caller. Generous for humans, a wall for
    // retry storms. Per-instance by design (a fleet shares state at the
    // gateway or in Redis); the map grows with caller cardinality · a real
    // deployment expires idle buckets.
    private static final java.util.Map<String, TokenBucket> buckets = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean allowed(HttpExchange ex) {
        String ip = ex.getRequestHeaders().getFirst("X-Forwarded-For");
        ip = ip != null ? ip.split(",")[0].trim() : ex.getRemoteAddress().getAddress().getHostAddress();
        return buckets.computeIfAbsent(ip, k -> new TokenBucket(60, 25, System.nanoTime()))
                .take(System.nanoTime());
    }

    // ------------------------------------------------------------- identity

    /**
     * The SSO seam. ANONYMOUS until somebody wires a validator in, which is
     * to say: until then this whole section is provably a no-op.
     *
     * A static field rather than constructor injection, which is how the
     * broker does it (BrokerApi holds a CallerIdentity field). The difference
     * is not taste · HttpApi is entirely static, from the private constructor
     * down to Main calling HttpApi.start(port), so there is no instance to
     * hang a field on. Making one exist purely to carry this would be a much
     * larger change than the feature, and every handler would still reach it
     * through a static anyway.
     *
     * volatile because Main wires it on the main thread and virtual threads
     * read it; written once at boot, read forever after.
     */
    private static volatile SsoIdentity identity = SsoIdentity.ANONYMOUS;

    /** Wire the validator · called once from Main (and from the lesson test). */
    public static void identity(SsoIdentity i) {
        identity = i == null ? SsoIdentity.ANONYMOUS : i;
    }

    /**
     * WHO THIS REQUEST IS, for the duration of this request.
     *
     * WHY A ThreadLocal IS SAFE HERE, and specifically why virtual threads
     * make it safer rather than riskier. The classic ThreadLocal hazard is a
     * POOLED thread: request A leaves a value behind, the pool hands the same
     * thread to request B, and B silently inherits A's identity · which on
     * this file would mean serving A's bank statement to B. That failure mode
     * is structurally absent here. The executor is
     * newVirtualThreadPerTaskExecutor (see start, above): every request gets a
     * brand-new virtual thread that is never reused and is garbage after the
     * response, so there is no second request to leak into. Set-and-clear in
     * handle is belt and braces, and mostly it is documentation · it makes the
     * scope of the value obvious to the next reader, and it keeps this correct
     * if the executor is ever changed to a pool by someone who did not read
     * this comment.
     *
     * ScopedValue (JEP 446) is the idiomatic Java 21 answer to exactly this
     * and would make the scoping structural rather than conventional. It is a
     * preview API, pom.xml sets maven.compiler.release to 21 with no
     * --enable-preview, and this codebase's whole point is that it compiles
     * with nothing special. Revisit when it leaves preview.
     */
    private static final ThreadLocal<Long> CALLER = new ThreadLocal<>();

    /**
     * The customer this request is allowed to act as · THE precedence rule.
     *
     * A token identifies its own customer and that wins; without one the
     * caller's parameter stands, exactly as before. See SsoIdentity.resolve
     * for why this is settled now rather than on activation day, and
     * BrokerApi.caller for the same three lines in the other service.
     *
     * Deliberately String in, String out. Every call site already holds the id
     * as the raw text it arrived as (a query fragment, or Json.num of a body)
     * and parses it later, inside a try that turns a bad number into a 400.
     * Handing back a String keeps that parse exactly where it was, which is
     * what makes the anonymous path BYTE-IDENTICAL to yesterday rather than
     * merely equivalent: when nobody is identified this method returns its own
     * argument and nothing downstream can tell it was called.
     *
     * A null argument with a token present is not an error · it is the good
     * case. The token alone says who is asking, so /api/statement with no
     * ?customer= at all starts working for an identified caller, while still
     * being the same 400 it always was for an anonymous one.
     */
    private static String caller(String requested) {
        Long identified = CALLER.get();
        return identified == null ? requested : identified.toString();
    }

    /**
     * Resolve the Authorization header into an identity, or into nothing.
     *
     * The catch is the permissive rollout written down. SsoIdentity's contract
     * already says an implementation must not throw, but the implementation is
     * a network call to auth.b4rruf3t.com wearing a lambda, and a JWKS fetch
     * timing out is an outage in the directory rather than in the bank. During
     * a dark launch that must degrade to "nobody is identified" · which is
     * precisely today's behaviour, on every route, with the demo still working
     * · instead of turning one slow dependency into a 500 across the whole
     * API. Falling through to the catch in handle would do the second thing.
     *
     * Note what is NOT here: no 401, no 403, no branch at all on the failure
     * being a missing header versus a forged one. Enforcement is a later,
     * human decision and it is not made in this method.
     */
    private static void attach(HttpExchange ex) {
        try {
            identity.customerFor(ex.getRequestHeaders().getFirst("Authorization"))
                    .ifPresent(CALLER::set);
        } catch (Throwable t) {
            // Throwable, not Exception, and the difference is the whole point.
            // The adapter this seam is built for is a lambda over a jar that is
            // not on the classpath yet · the first thing it can go wrong with on
            // activation day is NoClassDefFoundError, which is an Error. Catching
            // Exception here would let that escape into handle(), which also
            // catches only Exception, and kill the response outright: no 500, no
            // body, no metric. A dark launch that can black out the API on a
            // missing jar is not dark. Found by an adversarial review.
            System.err.println("sso: identity unavailable, continuing anonymously · " + t);
        }
    }

    private static void handle(HttpExchange ex, Handler h) throws IOException {
        long start = System.nanoTime();
        Response r;
        try {
            if (ex.getRequestURI().getPath().startsWith("/api/") && !allowed(ex)) {
                r = Response.json(429, "{\"error\":\"rate limited · the token bucket is empty; it refills at 25/s. " +
                        "Idempotency makes retries safe, this makes them cheap.\"}");
            } else {
                // Identity is attached HERE, once, for every route · rather
                // than in the eight handlers that read a customer id. The
                // difference matters on the day someone adds a ninth: a
                // per-handler version ships that route with no identity and
                // nobody notices, because nothing fails.
                //
                // The position within this method is chosen too. AFTER the 429
                // short-circuit, so a rate-limited request never pays for a
                // token validation. BEFORE the Metrics block and
                // sendResponseHeaders below, so nothing here can touch the
                // status, the body, the content type, or the route=/status=
                // labels. Attaching an identity changes WHOSE data a handler
                // reads and changes nothing else.
                attach(ex);
                try {
                    r = h.run(ex);
                } finally {
                    CALLER.remove();
                }
            }
        } catch (Exception e) {
            r = Response.json(500, "{\"error\":\"" + Json.esc(String.valueOf(e.getMessage())) + "\"}");
        }
        // every request is one Prometheus observation: a labeled counter and
        // a latency histogram · this is what /metrics exposes and Grafana graphs.
        // The scrape endpoint does not measure itself.
        String path = ex.getRequestURI().getPath();
        if (!"/metrics".equals(path)) {
            double seconds = (System.nanoTime() - start) / 1e9;
            Metrics.observeHttp(seconds);
            Metrics.inc("minibank_http_requests_total",
                    "route=\"" + routeClass(path) + "\",status=\"" + r.status() + "\"");
        }
        ex.getResponseHeaders().set("Content-Type", r.contentType());
        ex.sendResponseHeaders(r.status(), r.body().length);
        ex.getResponseBody().write(r.body());
        ex.close();
    }

    // a fixed set of route classes · the label is drawn ONLY from here, so a
    // hostile client cannot mint unbounded label values (a Prometheus
    // cardinality bomb) or inject a quote into the exposition by crafting a path
    private static final java.util.Set<String> ROUTES = java.util.Set.of(
            "accounts", "transfer", "relocate", "statement", "portfolio", "trade",
            "mortgage", "card", "signup", "support", "prices", "explorer", "kafka",
            "xray", "notifications");

    /** Collapse a path to one of a FIXED set of labels · /api/xray/summary -> /api/xray. */
    private static String routeClass(String path) {
        String[] p = path.split("/");
        if (p.length >= 3 && "api".equals(p[1])) return ROUTES.contains(p[2]) ? "/api/" + p[2] : "/api/other";
        return path.isEmpty() || "/".equals(path) ? "/" : "/static";
    }

    /** The customer's product shelf, valued at live prices. */
    private static Response portfolio(HttpExchange ex) throws Exception {
        String q = ex.getRequestURI().getQuery();
        String cust = null;
        if (q != null) for (String p : q.split("&")) if (p.startsWith("customer=")) cust = p.substring(9);
        cust = caller(cust);
        if (cust == null) return Response.json(400, "{\"error\":\"need ?customer=id\"}");
        long id = Long.parseLong(cust);
        Shard home = Shards.forCustomer(id);
        java.util.Map<Long, BigDecimal> bal = new java.util.HashMap<>();
        try (Connection c = home.open();
             var ps = c.prepareStatement("SELECT id, balance FROM accounts WHERE id = ? OR (id > ? AND id <= ?)")) {
            ps.setLong(1, id);
            ps.setLong(2, id);
            ps.setLong(3, id + 600);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) bal.put(rs.getLong(1), rs.getBigDecimal(2));
            }
        }
        PriceFeed.Px btc = PriceFeed.get("btc"), aapl = PriceFeed.get("aapl");
        FxClient.Rate fx = FxClient.usdToEur();
        BigDecimal btcU = bal.getOrDefault(id + Products.BTC, BigDecimal.ZERO);
        BigDecimal aaplU = bal.getOrDefault(id + Products.AAPL, BigDecimal.ZERO);
        return Response.json(200,
                "{\"main\":\"" + plain(bal.getOrDefault(id, BigDecimal.ZERO)) +
                "\",\"savings\":\"" + plain(bal.getOrDefault(id + Products.SAVINGS, BigDecimal.ZERO)) +
                "\",\"card\":\"" + plain(bal.getOrDefault(id + Products.CARD, BigDecimal.ZERO)) +
                "\",\"held\":\"" + plain(bal.getOrDefault(id + Products.HOLDS, BigDecimal.ZERO)) +
                "\",\"cardLimit\":\"1000\"" +
                ",\"btc\":\"" + plain(btcU) +
                "\",\"btcEur\":\"" + btcU.multiply(btc.price()).setScale(2, java.math.RoundingMode.HALF_DOWN).toPlainString() +
                "\",\"aapl\":\"" + plain(aaplU) +
                "\",\"aaplEur\":\"" + aaplU.multiply(aapl.price()).setScale(2, java.math.RoundingMode.HALF_DOWN).toPlainString() +
                "\",\"loan\":\"" + plain(bal.getOrDefault(id + Products.LOAN, BigDecimal.ZERO)) +
                "\",\"btcPrice\":\"" + plain(btc.price()) +
                "\",\"aaplPrice\":\"" + plain(aapl.price()) +
                "\",\"btcUsd\":\"" + plain(btc.usd()) +
                "\",\"aaplUsd\":\"" + plain(aapl.usd()) +
                "\",\"fxRate\":\"" + fx.rate().toPlainString() +
                "\",\"fxSource\":\"" + Json.esc(fx.source()) +
                "\",\"priceSource\":\"" + btc.source() + "\"}");
    }

    /** buy or sell an asset at the live price · one 4-entry, 2-currency tx */
    private static Response trade(HttpExchange ex) throws Exception {
        if (!"POST".equals(ex.getRequestMethod())) return Response.json(405, "{\"error\":\"POST only\"}");
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String txId = Json.str(body, "txId"), customer = caller(Json.num(body, "customer"));
        String asset = Json.str(body, "asset"), side = Json.str(body, "side"), eur = Json.str(body, "eur");
        if (txId == null || customer == null || asset == null || side == null || eur == null)
            return Response.json(400, "{\"error\":\"need txId, customer, asset, side, eur\"}");
        if (!"btc".equals(asset) && !"aapl".equals(asset))
            return Response.json(400, "{\"error\":\"asset must be btc or aapl\"}");
        try {
            PriceFeed.Px px = PriceFeed.get(asset);
            var result = Products.trade(UUID.fromString(txId), Long.parseLong(customer),
                    asset, "buy".equals(side), new BigDecimal(eur), px.price());
            Metrics.inc("minibank_ledger_events_total", "kind=\"trade\"");
            String kind = switch (result) {
                case Ledger.Ok ok -> "ok";
                case Ledger.AlreadyProcessed a -> "already_processed";
                case Ledger.InsufficientFunds i -> "insufficient_funds";
                case Ledger.NoSuchAccount n -> "no_such_account";
            };
            return Response.json(200, "{\"result\":\"" + kind + "\",\"price\":\"" + plain(px.price()) +
                    "\",\"source\":\"" + px.source() + "\"}");
        } catch (IllegalArgumentException e) {
            return Response.json(400, "{\"error\":\"" + Json.esc(e.getMessage()) + "\"}");
        }
    }

    /** the mortgage desk: instant double-entry disbursement, capped */
    private static Response mortgageApply(HttpExchange ex) throws Exception {
        if (!"POST".equals(ex.getRequestMethod())) return Response.json(405, "{\"error\":\"POST only\"}");
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String txId = Json.str(body, "txId"), customer = caller(Json.num(body, "customer")), amount = Json.str(body, "amount");
        if (txId == null || customer == null || amount == null)
            return Response.json(400, "{\"error\":\"need txId, customer, amount\"}");
        try {
            var result = Products.mortgage(UUID.fromString(txId), Long.parseLong(customer), new BigDecimal(amount));
            String kind = switch (result) {
                case Ledger.Ok ok -> "ok";
                case Ledger.AlreadyProcessed a -> "already_processed";
                case Ledger.InsufficientFunds i -> "insufficient_funds";
                case Ledger.NoSuchAccount n -> "no_such_account";
            };
            return Response.json(200, "{\"result\":\"" + kind + "\"}");
        } catch (IllegalArgumentException e) {
            return Response.json(400, "{\"error\":\"" + Json.esc(e.getMessage()) + "\"}");
        }
    }

    // Rita's own meters: the model call costs real money, so it gets its
    // own buckets IN FRONT of it · a few questions per caller, a daily
    // global allowance, 429 with grace beyond that.
    private static final java.util.Map<String, TokenBucket> supportBuckets = new java.util.concurrent.ConcurrentHashMap<>();
    private static final TokenBucket supportGlobal = new TokenBucket(300, 300.0 / 86400, System.nanoTime());

    /** Rita, the support agent · grounded in live account data, read-only */
    private static Response support(HttpExchange ex) throws Exception {
        if (!"POST".equals(ex.getRequestMethod())) return Response.json(405, "{\"error\":\"POST only\"}");
        if (!SupportAgent.enabled())
            return Response.json(200, "{\"reply\":\"Rita is offline in this environment (no model key configured) · but the Guide on the X-ray tab explains everything I would have, at three depths.\"}");
        String ip = ex.getRequestHeaders().getFirst("X-Forwarded-For");
        ip = ip != null ? ip.split(",")[0].trim() : ex.getRemoteAddress().getAddress().getHostAddress();
        if (!supportBuckets.computeIfAbsent(ip, k -> new TokenBucket(4, 1.0 / 15, System.nanoTime())).take(System.nanoTime())
                || !supportGlobal.take(System.nanoTime()))
            return Response.json(429, "{\"reply\":\"I need a tiny breather · ask me again in a moment. (My own token bucket: even support is rate-limited here.)\"}");

        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String customer = caller(Json.num(body, "customer")), message = Json.str(body, "message"), transcript = Json.str(body, "transcript");
        if (customer == null || message == null) return Response.json(400, "{\"error\":\"need customer, message\"}");
        if (message.length() > 400) message = message.substring(0, 400);
        if (transcript != null && transcript.length() > 1600) transcript = transcript.substring(transcript.length() - 1600);
        try {
            return Response.json(200, SupportAgent.replyJson(Long.parseLong(customer), message, transcript));
        } catch (Exception e) {
            System.err.println("support: " + e);
            return Response.json(200, "{\"reply\":\"Sorry · I hiccuped mid-thought. Try once more? (If this keeps happening the model behind me is having a day.)\"}");
        }
    }

    /** self-serve onboarding: pick a name and a REGION · residency is a
     *  choice you make at signup, exactly like the real product. The new
     *  customer gets an account on their region's shard, 500 from the
     *  world, and the full product shelf. */
    /* DELIBERATELY NOT caller()-WRAPPED. Signup MINTS a customer id rather
       than reading one, so there is nothing here for a token to override ·
       an identity override on this route would mean "sign up as somebody who
       already exists", which is not a thing. What belongs here eventually is
       the opposite direction: after Directory.register succeeds, bind the
       token's subject to the fresh id with Directory.linkSso(sub, id), which
       is the only write that ever creates an identity. It is not wired yet
       because the seam (SsoIdentity) answers "which customer" and not "which
       subject" · deliberately, since which customer is the only question the
       nine reading routes have. Extending it to hand back the subject is a
       one-method change, and the day it happens this is the line it lands on. */
    private static Response signup(HttpExchange ex) throws Exception {
        if (!"POST".equals(ex.getRequestMethod())) return Response.json(405, "{\"error\":\"POST only\"}");
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String name = Json.str(body, "name"), region = Json.str(body, "region");
        if (name == null || region == null) return Response.json(400, "{\"error\":\"need name, region\"}");
        name = name.trim().toLowerCase();
        if (!name.matches("[a-z]{3,12}"))
            return Response.json(400, "{\"error\":\"name: 3-12 letters, a-z only\"}");
        int shard = "uk".equals(region) ? 1 : 0;

        long id;
        try (Connection c = Directory.openForRead(); var st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM customers WHERE customer_id < 100")) {
                rs.next();
                if (rs.getLong(1) >= 25)
                    return Response.json(409, "{\"error\":\"the demo cast is full (25 customers) · relocate someone instead\"}");
            }
            try (var ps = c.prepareStatement("SELECT 1 FROM customers WHERE owner = ? AND customer_id < 100")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return Response.json(409, "{\"error\":\"that name is taken\"}");
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(customer_id), 9) + 1 FROM customers WHERE customer_id < 100")) {
                rs.next();
                id = rs.getLong(1);
            }
        }
        // first-write-wins in the directory settles races on the id
        Directory.register(id, name, shard);
        if (!name.equals(Directory.owner(id)))
            return Response.json(409, "{\"error\":\"two signups collided · try again\"}");
        Shard home = Shards.s(shard);
        home.createCustomer(id, name);
        home.transferLocal(UUID.randomUUID(), Shard.WORLD, id, new BigDecimal("500.00"));
        Products.ensureFor(id);
        return Response.json(200, "{\"result\":\"ok\",\"id\":" + id + ",\"region\":\"" + Shards.regionName(shard) + "\"}");
    }

    /** real 30-day price series for the product charts */
    private static Response priceHistory(HttpExchange ex) throws Exception {
        String q = ex.getRequestURI().getQuery();
        String asset = null;
        if (q != null) for (String p : q.split("&")) if (p.startsWith("asset=")) asset = p.substring(6);
        if (!"btc".equals(asset) && !"aapl".equals(asset))
            return Response.json(400, "{\"error\":\"asset must be btc or aapl\"}");
        return Response.json(200, "{\"asset\":\"" + asset + "\",\"points\":" + PriceFeed.historyJson(asset) + "}");
    }

    // ------------------------------------------------------------------ console

    private static Response explorerCatalog(HttpExchange ex) throws Exception {
        return Response.json(200, Explorer.catalogJson());
    }

    private static Response explorerRun(HttpExchange ex) throws Exception {
        String q = ex.getRequestURI().getQuery();
        String db = null, qid = null;
        if (q != null) for (String s : q.split("&")) {
            if (s.startsWith("db=")) db = s.substring(3);
            if (s.startsWith("q=")) qid = s.substring(2);
        }
        if (db == null || qid == null) return Response.json(400, "{\"error\":\"need db, q\"}");
        try {
            return Response.json(200, Explorer.runJson(db, qid));
        } catch (IllegalArgumentException e) {
            return Response.json(400, "{\"error\":\"" + Json.esc(e.getMessage()) + "\"}");
        }
    }

    private static Response kafkaConsole(HttpExchange ex) throws Exception {
        String kafka = System.getenv().getOrDefault("MINIBANK_KAFKA", "localhost:9092");
        return Response.json(200, KafkaConsole.consoleJson(kafka));
    }

    /** the card network's three verbs: authorize (hold), capture, release */

    // ------------------------------------------------------------- the issuer

    /**
     * What an acquirer may know about an instrument, which is deliberately
     * almost nothing: whether it is usable, and what a receipt may say.
     *
     * There is no availability here and there never will be. An acquirer that
     * could ask "how much is left" would eventually check before authorising,
     * and a check before a decision is a race by construction: the answer is
     * stale the moment it is given. Authorisation is the only honest question,
     * because it is the one whose answer is taken under the lock that makes it
     * true.
     */
    private static Response issuerInstrument(HttpExchange ex) throws Exception {
        // POST issues a card to a customer. That is a CARDHOLDER action, not an
        // acquirer one, and it sits on this route family only because the
        // instrument is what the route is about. An acquirer has no business
        // minting instruments and would have nothing to name a customer with
        // if it tried.
        if ("POST".equals(ex.getRequestMethod())) {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            // the ONE identity-bearing read on the /issuer family · and it is
            // here because issuing is a cardholder action wearing an acquirer
            // route. authorizations and clearing below stay token- and
            // batch-scoped: an acquirer must never acquire a cardholder.
            String customer = caller(Json.num(body, "customer"));
            if (customer == null) return Response.json(400, "{\"error\":\"need customer\"}");
            Issuer.Instrument i = Issuer.issueCard(Long.parseLong(customer));
            return Response.json(200, "{\"token\":\"" + Json.esc(i.token())
                    + "\",\"brand_label\":\"" + Json.esc(i.brandLabel())
                    + "\",\"last4\":\"" + Json.esc(i.last4())
                    + "\",\"status\":\"" + Json.esc(i.status()) + "\"}");
        }
        String path = ex.getRequestURI().getPath();
        String token = path.substring(path.lastIndexOf('/') + 1);
        if (token.isBlank() || token.equals("instruments"))
            return Response.json(400, "{\"error\":\"GET /issuer/v1/instruments/{token}\"}");
        try {
            Issuer.Instrument i = Issuer.describe(token);
            return Response.json(200, "{\"token\":\"" + Json.esc(i.token())
                    + "\",\"brand_label\":\"" + Json.esc(i.brandLabel())
                    + "\",\"last4\":\"" + Json.esc(i.last4())
                    + "\",\"status\":\"" + Json.esc(i.status()) + "\"}");
        } catch (Issuer.UnknownInstrument e) {
            return Response.json(404, "{\"error\":\"unknown instrument\"}");
        }
    }

    /**
     * Authorise, capture or void, from the acquirer.
     *
     * The authorisation id is MINTED BY THE CALLER and is the idempotency key.
     * A card network retries, and a bank that holds a customer's money twice
     * because the network was uncertain is worse than one that occasionally
     * answers late.
     */
    private static Response issuerAuthorize(HttpExchange ex) throws Exception {
        if (!"POST".equals(ex.getRequestMethod())) return Response.json(405, "{\"error\":\"POST only\"}");
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = ex.getRequestURI().getPath();
        String action = path.endsWith("/capture") ? "capture" : path.endsWith("/void") ? "void" : "authorize";

        try {
            Instant at = businessTime(Json.str(body, "business_at"));
            if (!"authorize".equals(action)) {
                String idText = path.substring(path.lastIndexOf("/authorizations/") + 16);
                idText = idText.substring(0, idText.indexOf('/'));
                UUID id = UUID.fromString(idText);
                boolean ok = "capture".equals(action) ? Issuer.capture(id, at) : Issuer.voidAuthorization(id, at);
                return Response.json(ok ? 200 : 409,
                        "{\"authorization\":\"" + id + "\",\"" + action + "d\":" + ok + "}");
            }

            String token = Json.str(body, "instrument");
            String idem = Json.str(body, "authorization_id");
            String amount = Json.str(body, "amount");
            String currency = Json.str(body, "currency");
            if (token == null || idem == null || amount == null)
                return Response.json(400, "{\"error\":\"need instrument, authorization_id, amount\"}");

            Issuer.Decision d = Issuer.authorize(UUID.fromString(idem), token,
                    new java.math.BigDecimal(amount), currency, at);
            // An APPROVED and a DECLINED are both successful answers to the
            // question asked, so both are 200. A decline is not an error: it is
            // the bank doing its job, and a 4xx would tempt the acquirer to
            // retry it as though it were a fault.
            return Response.json(200, "{\"authorization\":\"" + d.authorizationId()
                    + "\",\"approved\":" + d.approved()
                    + (d.reason() == null ? "" : ",\"reason\":\"" + Json.esc(d.reason()) + "\"") + "}");
        } catch (IllegalArgumentException e) {
            return Response.json(400, "{\"error\":\"" + Json.esc(String.valueOf(e.getMessage())) + "\"}");
        }
    }

    /**
     * Accept a clearing batch from an acquirer.
     *
     * The reply carries the issuer's OWN total, not the one it was sent. Two
     * organisations sharing no database computing the same figure and then
     * comparing is the entire mechanism, and an issuer that echoed the
     * acquirer's number back would make every reconciliation pass and mean
     * nothing.
     */
    private static Response issuerClearing(HttpExchange ex) throws Exception {
        if (!"POST".equals(ex.getRequestMethod())) return Response.json(405, "{\"error\":\"POST only\"}");
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            String batch = Json.str(body, "batch");
            String currency = Json.str(body, "currency");
            String date = Json.str(body, "business_date");
            if (batch == null || date == null) return Response.json(400, "{\"error\":\"need batch, business_date\"}");

            // The lines, scanned out of the array. The codec here is a scanner
            // rather than a parser, so the pairs are read positionally, which is
            // fine for a flat array and stated plainly as the limit it is.
            java.util.List<java.util.Map.Entry<java.util.UUID, java.math.BigDecimal>> lines =
                    new java.util.ArrayList<>();
            java.util.List<String> refs = Json.each(body, "authorization");
            java.util.List<String> amounts = Json.each(body, "amount");
            for (int i = 0; i < Math.min(refs.size(), amounts.size()); i++) {
                lines.add(java.util.Map.entry(java.util.UUID.fromString(refs.get(i)),
                        new java.math.BigDecimal(amounts.get(i))));
            }

            Issuer.Cleared c = Issuer.clear(batch, currency == null ? "EUR" : currency,
                    java.time.LocalDate.parse(date),
                    new java.math.BigDecimal(orElse(Json.str(body, "gross"), "0")),
                    new java.math.BigDecimal(orElse(Json.str(body, "net"), "0")),
                    lines, businessTime(Json.str(body, "business_at")));

            return Response.json(200, "{\"reference\":\"" + Json.esc(c.batchId())
                    + "\",\"gross\":\"" + c.settledGross().toPlainString()
                    + "\",\"interchange\":\"" + c.interchange().toPlainString()
                    + "\",\"net\":\"" + c.settledNet().toPlainString()
                    + "\",\"matched\":" + c.matched() + ",\"unmatched\":" + c.unmatched() + "}");
        } catch (IllegalArgumentException e) {
            return Response.json(400, "{\"error\":\"" + Json.esc(String.valueOf(e.getMessage())) + "\"}");
        }
    }

    private static String orElse(String v, String fallback) { return v == null || v.isBlank() ? fallback : v; }

    /** Absent means now, because a browser has no business time. Unreadable is
     *  a caller bug and is said so, never silently replaced with the clock. */
    private static Instant businessTime(String raw) {
        if (raw == null || raw.isBlank()) return Instant.now();
        try { return Instant.parse(raw); }
        catch (Exception e) { throw new IllegalArgumentException("business_at is not an ISO-8601 instant: " + raw); }
    }

    private static Response cardOps(HttpExchange ex) throws Exception {
        if (!"POST".equals(ex.getRequestMethod())) return Response.json(405, "{\"error\":\"POST only\"}");
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String action = Json.str(body, "action"), customer = caller(Json.num(body, "customer")), tx = Json.str(body, "tx");
        String amount = Json.str(body, "amount");
        if (action == null || customer == null || tx == null)
            return Response.json(400, "{\"error\":\"need action, customer, tx\"}");
        try {
            long id = Long.parseLong(customer);
            UUID txId = UUID.fromString(tx);
            var result = switch (action) {
                case "authorize" -> Products.authorize(txId, id, new BigDecimal(amount));
                case "capture" -> Products.capture(txId, id);
                case "release" -> Products.release(txId, id);
                default -> throw new IllegalArgumentException("action: authorize|capture|release");
            };
            String kind = switch (result) {
                case Ledger.Ok ok -> "ok";
                case Ledger.AlreadyProcessed a -> "already_processed";
                case Ledger.InsufficientFunds i -> "insufficient_funds";
                case Ledger.NoSuchAccount n -> "no_such_account";
            };
            return Response.json(200, "{\"result\":\"" + kind + "\"}");
        } catch (IllegalArgumentException e) {
            return Response.json(400, "{\"error\":\"" + Json.esc(e.getMessage()) + "\"}");
        }
    }

    /** NUMERIC(20,8) prints trailing zeros; humans don't want them */
    private static String plain(java.math.BigDecimal v) {
        java.math.BigDecimal s = v.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
    }

    private static long one(ResultSet rs) throws Exception {
        rs.next();
        return rs.getLong(1);
    }
}
