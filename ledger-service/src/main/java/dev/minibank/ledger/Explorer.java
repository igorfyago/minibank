package dev.minibank.ledger;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL STUDIO — the real databases behind the bank, browsable like an IDE.
 *
 * The web page shows the exact SQL and the exact rows, but the CATALOG is
 * fixed: the client sends only (database id, query id) and the server runs
 * a named query from this file. A public bank demo does not execute
 * arbitrary SQL from the internet — the IDE look is real, the injection
 * surface is zero.
 */
public final class Explorer {

    public record Q(String id, String title, String sql) {}

    private Explorer() {}

    private static final List<Q> SHARD_QUERIES = List.of(
        new Q("accounts", "accounts — every account on this machine", """
            SELECT id, owner, kind, currency, trim_scale(balance) AS balance
            FROM accounts
            ORDER BY id"""),
        new Q("entries", "entries — the double-entry truth (latest 30)", """
            SELECT e.id, substr(e.tx_id::text, 1, 8) AS tx, a.owner, a.currency,
                   trim_scale(e.amount) AS amount,
                   to_char(e.created_at, 'HH24:MI:SS') AS at
            FROM entries e
            JOIN accounts a ON a.id = e.account_id
            ORDER BY e.id DESC
            LIMIT 30"""),
        new Q("transactions", "transactions — the idempotency gates (latest 25)", """
            SELECT substr(id::text, 1, 13) AS tx_id, kind,
                   to_char(created_at, 'HH24:MI:SS') AS at
            FROM transactions
            ORDER BY created_at DESC
            LIMIT 25"""),
        new Q("outbox", "outbox — events born inside money commits (latest 20)", """
            SELECT id, key,
                   CASE WHEN published_at IS NULL THEN 'PENDING' ELSE 'published' END AS state,
                   to_char(created_at, 'HH24:MI:SS') AS created
            FROM outbox
            ORDER BY id DESC
            LIMIT 20"""),
        new Q("audit_sumzero", "AUDIT — every tx sums to zero per currency (expect 0 rows)", """
            SELECT e.tx_id, a.currency, SUM(e.amount) AS should_be_zero
            FROM entries e
            JOIN accounts a ON a.id = e.account_id
            GROUP BY e.tx_id, a.currency
            HAVING SUM(e.amount) <> 0"""),
        new Q("audit_drift", "AUDIT — cached balance = SUM(entries) (expect 0 rows)", """
            SELECT a.id, a.owner, trim_scale(a.balance) AS cached,
                   trim_scale(COALESCE(e.s, 0)) AS from_ledger
            FROM accounts a
            LEFT JOIN (SELECT account_id, SUM(amount) AS s
                       FROM entries GROUP BY account_id) e
                   ON e.account_id = a.id
            WHERE a.balance <> COALESCE(e.s, 0)"""),
        new Q("in_flight", "in_transit — this region's slice of money in the pipe", """
            SELECT owner, currency, trim_scale(balance) AS balance
            FROM accounts
            WHERE id = 3"""));

    private static final Map<String, List<Q>> CATALOG = new LinkedHashMap<>();
    static {
        CATALOG.put("eu", SHARD_QUERIES);
        CATALOG.put("uk", SHARD_QUERIES);
        CATALOG.put("directory", List.of(
            new Q("customers", "customers — the routing table (who lives where)", """
                SELECT customer_id, owner,
                       CASE WHEN shard = 0 THEN 'eu' ELSE 'uk' END AS region, moving
                FROM customers
                ORDER BY customer_id""")));
        CATALOG.put("notifications", List.of(
            new Q("notifications", "notifications — the idempotent consumer's own table (latest 20)", """
                SELECT substr(event_key, 1, 22) AS event_key,
                       substr(message, 1, 60) AS message,
                       to_char(created_at, 'HH24:MI:SS') AS at
                FROM notifications
                ORDER BY created_at DESC
                LIMIT 20""")));
    }

    private static final Map<String, String> DB_TITLES = Map.of(
        "eu", "eu — PostgreSQL 16 · :5434",
        "uk", "uk — PostgreSQL 16 · :5435",
        "directory", "directory — pg-control · minibank_directory",
        "notifications", "notifications — pg-control · minibank_notifications");

    public static String catalogJson() {
        StringBuilder b = new StringBuilder("{\"dbs\":[");
        boolean firstDb = true;
        for (var e : CATALOG.entrySet()) {
            if (!firstDb) b.append(',');
            firstDb = false;
            b.append("{\"id\":\"").append(e.getKey())
             .append("\",\"title\":\"").append(Json.esc(DB_TITLES.get(e.getKey())))
             .append("\",\"queries\":[");
            boolean firstQ = true;
            for (Q q : e.getValue()) {
                if (!firstQ) b.append(',');
                firstQ = false;
                b.append("{\"id\":\"").append(q.id())
                 .append("\",\"title\":\"").append(Json.esc(q.title())).append("\"}");
            }
            b.append("]}");
        }
        return b.append("]}").toString();
    }

    public static String runJson(String db, String qid) throws Exception {
        List<Q> qs = CATALOG.get(db);
        if (qs == null) throw new IllegalArgumentException("unknown database");
        Q q = qs.stream().filter(x -> x.id().equals(qid)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown query"));

        long t0 = System.nanoTime();
        StringBuilder b = new StringBuilder();
        try (Connection c = open(db);
             var st = c.createStatement();
             ResultSet rs = st.executeQuery(q.sql())) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            b.append(",\"cols\":[");
            for (int i = 1; i <= n; i++) {
                if (i > 1) b.append(',');
                b.append('"').append(Json.esc(md.getColumnLabel(i))).append('"');
            }
            b.append("],\"rows\":[");
            int count = 0;
            while (rs.next() && count < 100) {
                if (count++ > 0) b.append(',');
                b.append('[');
                for (int i = 1; i <= n; i++) {
                    if (i > 1) b.append(',');
                    Object v = rs.getObject(i);
                    b.append('"').append(Json.esc(String.valueOf(v))).append('"');
                }
                b.append(']');
            }
            b.append(']');
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        return "{\"sql\":\"" + Json.esc(q.sql()) + "\",\"title\":\"" + Json.esc(q.title()) +
               "\",\"ms\":" + ms + b + "}";
    }

    private static Connection open(String db) throws SQLException {
        return switch (db) {
            case "eu" -> Shards.s(0).open();
            case "uk" -> Shards.s(1).open();
            case "directory" -> Directory.openForRead();
            case "notifications" -> Notifications.openForRead();
            default -> throw new IllegalArgumentException("unknown database");
        };
    }
}
