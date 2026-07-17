package dev.minibank.ledger;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * RITA — the support agent, built the only way an agent belongs on a
 * public bank demo:
 *
 *   GROUNDED   every reply starts from the customer's REAL balances and
 *              recent ledger rows, queried at request time and injected
 *              into the prompt. Rita knows your pending payment exists
 *              because the ledger says so.
 *   READ-ONLY  Rita has no tools that move money. Not "instructed not
 *              to" — there is no code path. Least privilege beats
 *              prompt engineering; a prompt injection can make her say
 *              silly things, never move a cent.
 *   METERED    her own token buckets (per caller and global) sit in
 *              front of the model call. Curiosity is welcome; abuse
 *              hits 429 before it hits the API bill.
 *
 * The model call itself is the JDK HttpClient and hand-built JSON —
 * no SDK, same doctrine as everything else here. No key in the
 * environment? Rita is honestly offline.
 */
public final class SupportAgent {

    private static final String KEY = System.getenv("OPENAI_API_KEY");
    private static final String MODEL = System.getenv().getOrDefault("SUPPORT_MODEL", "gpt-4o-mini");
    private static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private SupportAgent() {}

    public static boolean enabled() {
        return KEY != null && !KEY.isBlank();
    }

    public static String reply(long customerId, String message, String transcript) throws Exception {
        String system = persona(customerId, transcript);
        String body = "{\"model\":\"" + MODEL + "\",\"temperature\":0.4,\"max_tokens\":400," +
                "\"messages\":[{\"role\":\"system\",\"content\":\"" + Json.esc(system) + "\"}," +
                "{\"role\":\"user\",\"content\":\"" + Json.esc(message) + "\"}]}";
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/chat/completions"))
                        .timeout(Duration.ofSeconds(25))
                        .header("Authorization", "Bearer " + KEY)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IllegalStateException("model HTTP " + r.statusCode());
        String content = extractContent(r.body());
        if (content == null || content.isBlank()) throw new IllegalStateException("empty reply");
        return content;
    }

    /** who Rita is + what is TRUE about this customer, right now */
    private static String persona(long customerId, String transcript) throws Exception {
        Shard home = Shards.forCustomer(customerId);
        String region = Shards.regionName(home.index);
        Map<Long, BigDecimal> bal = new HashMap<>();
        StringBuilder recent = new StringBuilder();
        String owner = "customer";
        try (Connection c = home.open()) {
            try (var ps = c.prepareStatement("SELECT id, owner, balance FROM accounts WHERE id = ? OR (id > ? AND id <= ?)")) {
                ps.setLong(1, customerId);
                ps.setLong(2, customerId);
                ps.setLong(3, customerId + 600);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        bal.put(rs.getLong(1), rs.getBigDecimal(3));
                        if (rs.getLong(1) == customerId) owner = rs.getString(2);
                    }
                }
            }
            try (var ps = c.prepareStatement("""
                    SELECT t.kind, e.amount, COALESCE(o.owner,''), to_char(e.created_at,'HH24:MI')
                    FROM entries e JOIN transactions t ON t.id = e.tx_id
                    LEFT JOIN LATERAL (SELECT oa.owner FROM entries o2 JOIN accounts oa ON oa.id = o2.account_id
                                       WHERE o2.tx_id = e.tx_id AND o2.id <> e.id ORDER BY o2.id LIMIT 1) o ON true
                    WHERE e.account_id = ? ORDER BY e.id DESC LIMIT 6""")) {
                ps.setLong(1, customerId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        recent.append(rs.getString(4)).append(' ').append(rs.getString(1)).append(' ')
                              .append(rs.getBigDecimal(2).stripTrailingZeros().toPlainString())
                              .append(" (").append(rs.getString(3)).append("); ");
                    }
                }
            }
        }
        BigDecimal inFlight = Shards.inFlight();
        PriceFeed.Px btc = PriceFeed.get("btc"), aapl = PriceFeed.get("aapl");

        return """
            You are Rita, the support agent of minibank — a real, running demo neobank built from first \
            principles in raw Java 21 (no frameworks) by Igor Yago, live at bank.b4rruf3t.com. Be warm, \
            concise (under 120 words unless asked to go deep), plain-spoken, lightly playful. Never invent \
            data. You can SEE the customer's account (below) but you CANNOT move money — there is no code \
            path for it; if asked to transact, point to the app's buttons.

            THE BANK: customers live in a region (eu or uk) — each region is its own Postgres server; a \
            directory routes by residency. Payments inside a region are one ACID transaction (pessimistic \
            FOR UPDATE locks, ordered; caller-owned idempotency keys). Cross-region payments are a saga: \
            money departs into an in_transit clearing account, rides Kafka, arrives idempotently — the app \
            shows PENDING until the arrival commits (usually under a second; consumers rebalancing can \
            delay it). Double-entry ledger, append-only by database trigger; balances are projections; \
            audits run live on the X-ray tab, which also traces any payment end to end (the Show button \
            runs a 60s guided demo). PRODUCTS: Savings (a second account; ±€50 buttons); Credit card \
            (limit €1000 enforced by a CHECK constraint; café pays €12, hold/capture/release = real card \
            authorization); Bitcoin and Apple stock (multi-currency ledger, live prices, buy/sell €50); \
            Mortgage (up to €20,000; disbursement leaves net worth unchanged; repay €500). Add money tops \
            up €100 from the world. Relocate moves a customer between regions (balance travels as a saga, \
            then the directory pointer flips). New users: the + chip.

            THIS CUSTOMER: %s, region %s. Balances: main €%s, savings €%s, card €%s (held €%s), \
            bitcoin %s BTC (price €%s), apple %s shares (price €%s), mortgage €%s. Money in flight \
            fleet-wide right now: €%s. Recent ledger rows (time kind amount counterparty): %s

            Conversation so far (may be empty): %s""".formatted(
                owner, region,
                p(bal.get(customerId)), p(bal.get(customerId + Products.SAVINGS)),
                p(bal.get(customerId + Products.CARD)), p(bal.get(customerId + Products.HOLDS)),
                p(bal.get(customerId + Products.BTC)), btc.price().toPlainString(),
                p(bal.get(customerId + Products.AAPL)), aapl.price().toPlainString(),
                p(bal.get(customerId + Products.LOAN)), inFlight.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(),
                recent.isEmpty() ? "none yet" : recent.toString(),
                transcript == null || transcript.isBlank() ? "(first message)" : transcript);
    }

    private static String p(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    /** walk the completion JSON for choices[0].message.content — a tiny
     *  honest parser instead of a JSON library, escapes and all */
    static String extractContent(String json) {
        int i = json.indexOf("\"content\":");
        if (i < 0) return null;
        i = json.indexOf('"', i + 10);
        if (i < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int q = i + 1; q < json.length(); q++) {
            char ch = json.charAt(q);
            if (ch == '\\') {
                char n = json.charAt(++q);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> { }
                    case 'u' -> { sb.append((char) Integer.parseInt(json.substring(q + 1, q + 5), 16)); q += 4; }
                    default -> sb.append(n);
                }
            } else if (ch == '"') break;
            else sb.append(ch);
        }
        return sb.toString();
    }
}
