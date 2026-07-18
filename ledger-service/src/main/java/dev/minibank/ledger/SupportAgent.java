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
 * RITA · the support agent, now with HANDS. The design rule Igor set:
 * agents and humans have the same power; the human is in the loop.
 *
 *   GROUNDED   every reply starts from the customer's REAL balances,
 *              recent ledger rows and the cast list, queried at request
 *              time and injected into the prompt.
 *   TOOLED     Rita has the same verbs as the app's buttons: send, top
 *              up, savings, card lifecycle, trade, loan, relocate.
 *              She PROPOSES a tool call; she never executes.
 *   APPROVED   the proposal renders as an Allow/Deny card in the chat.
 *              On Allow, the CUSTOMER'S BROWSER makes the same public
 *              API call the buttons make · same idempotency keys, same
 *              rate limiter, same CHECK constraints. Deny = nothing.
 *              The gate is a click, not a promise.
 *   METERED    her own token buckets sit in front of the model call.
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

    /** the same powers as the app's buttons · one tool per verb */
    private static final String TOOLS = """
        [
        {"type":"function","function":{"name":"send_money","description":"Send euros from the customer's main account to another customer (cross-region works: it becomes a saga).","parameters":{"type":"object","properties":{"to":{"type":"string","description":"recipient's name from the CAST list, e.g. oscar"},"amount":{"type":"string","description":"EUR amount, e.g. 50.00"}},"required":["to","amount"]}}},
        {"type":"function","function":{"name":"add_money","description":"Top up the customer's main account from the world (max 500 per top-up).","parameters":{"type":"object","properties":{"amount":{"type":"string","description":"EUR amount up to 500.00"}},"required":["amount"]}}},
        {"type":"function","function":{"name":"savings_move","description":"Move euros between main and savings.","parameters":{"type":"object","properties":{"direction":{"type":"string","enum":["to_savings","to_main"]},"amount":{"type":"string"}},"required":["direction","amount"]}}},
        {"type":"function","function":{"name":"card_action","description":"Credit card operations. pay_cafe spends at the cafe; hold authorizes (reserves); capture pays the most recent hold to the merchant; release undoes the most recent hold; repay pays the card debt from main.","parameters":{"type":"object","properties":{"action":{"type":"string","enum":["pay_cafe","hold","capture","release","repay"]},"amount":{"type":"string","description":"EUR amount for pay_cafe, hold or repay"}},"required":["action"]}}},
        {"type":"function","function":{"name":"trade","description":"Buy or sell bitcoin (btc) or Apple stock (aapl) at the live price, for a EUR amount.","parameters":{"type":"object","properties":{"asset":{"type":"string","enum":["btc","aapl"]},"side":{"type":"string","enum":["buy","sell"]},"eur":{"type":"string","description":"EUR amount, e.g. 50.00"}},"required":["asset","side","eur"]}}},
        {"type":"function","function":{"name":"loan","description":"apply for a loan (instant decision, up to 20000) or repay part of it from main.","parameters":{"type":"object","properties":{"action":{"type":"string","enum":["apply","repay"]},"amount":{"type":"string"}},"required":["action","amount"]}}},
        {"type":"function","function":{"name":"relocate","description":"Move the customer to the other region · the balance travels as one saga, then the directory pointer flips.","parameters":{"type":"object","properties":{"region":{"type":"string","enum":["eu","uk"]}},"required":["region"]}}}
        ]""";

    /** returns the FULL response body for the endpoint:
     *  {"reply": "...", "action": {"tool": "...", "args": "..."}} */
    public static String replyJson(long customerId, String message, String transcript) throws Exception {
        String system = persona(customerId, transcript);
        String body = "{\"model\":\"" + MODEL + "\",\"temperature\":0.4,\"max_tokens\":400," +
                "\"tools\":" + TOOLS + ",\"tool_choice\":\"auto\"," +
                "\"messages\":[{\"role\":\"system\",\"content\":\"" + Json.esc(system) + "\"}," +
                "{\"role\":\"user\",\"content\":\"" + Json.esc(message) + "\"}]}";
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/chat/completions"))
                        .timeout(Duration.ofSeconds(25))
                        .header("Authorization", "Bearer " + KEY)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IllegalStateException("model HTTP " + r.statusCode());
        String json = r.body();

        String text = extractString(json, "content");
        String tool = null, args = null;
        int tc = json.indexOf("\"tool_calls\"");
        if (tc >= 0) {
            tool = extractString(json.substring(tc), "name");
            args = extractString(json.substring(tc), "arguments");
        }
        if ((text == null || text.isBlank()) && tool == null)
            throw new IllegalStateException("empty reply");

        StringBuilder out = new StringBuilder("{\"reply\":\"").append(Json.esc(text == null ? "" : text)).append('"');
        if (tool != null && args != null) {
            out.append(",\"action\":{\"tool\":\"").append(Json.esc(tool))
               .append("\",\"args\":\"").append(Json.esc(args)).append("\"}");
        }
        return out.append('}').toString();
    }

    /** who Rita is + what is TRUE about this customer and the cast, right now */
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
        StringBuilder cast = new StringBuilder();
        try (Connection c = Directory.openForRead();
             var st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT owner, customer_id, CASE WHEN shard = 0 THEN 'eu' ELSE 'uk' END FROM customers WHERE customer_id < 100 ORDER BY customer_id")) {
            while (rs.next()) {
                cast.append(rs.getString(1)).append('=').append(rs.getLong(2))
                    .append(" (").append(rs.getString(3)).append("); ");
            }
        }
        BigDecimal inFlight = Shards.inFlight();
        PriceFeed.Px btc = PriceFeed.get("btc"), aapl = PriceFeed.get("aapl");

        return """
            You are Rita, the support agent of minibank · a real, running demo neobank built from first \
            principles in raw Java 21 (no frameworks) by Igor Yago, live at bank.b4rruf3t.com. Be warm, \
            concise (under 100 words unless asked to go deep), plain-spoken, lightly playful. Never invent data. Never use em dashes; use commas or periods.

            YOU CAN ACT. You have tools with exactly the same powers as the app's buttons. When the customer \
            asks for an action, CALL the right tool immediately · the app shows them a friendly Allow/Deny \
            card and NOTHING happens until they press Allow, so do not ask for confirmation yourself and \
            never claim you cannot act. One tool call per message. Resolve recipient names from the CAST \
            list. Amounts are EUR strings like "50.00". The trade tool takes EUR: for "10 AAPL shares", \
            multiply shares by the live price and call trade with that EUR amount. capture/release apply \
            to the most recent hold of this session. HARD RULE: never SAY you will do, proceed with, or \
            handle an action - either CALL the tool in this very reply, or state you cannot. Announcing \
            an action without a tool call is a failure. If a request needs no action, just answer.

            YOU ALSO EXPLAIN THE SYSTEM · when asked how the bank is built, works, or scales, answer             like the engineer who built it: concrete components, exact technologies, real numbers,             mechanism first, consequence second. No hedging, no filler, no defining jargon unless asked ·             state facts until the design is obvious. Up to 250 words for architecture answers.

            THE EXACT STACK (all really running): Edge: Caddy 2 (TLS, Let's Encrypt) -> per-caller token             bucket (60 burst, refill 25/s, 429 when empty) -> raw JDK HttpServer, one Java 21 virtual             thread per request, zero frameworks. Routing: a directory service with its own Postgres             database (minibank_directory) maps customer -> region plus a moving flag, cached in-process.             Two regions, eu and uk: PostgreSQL 16 each, hand-rolled connection pool (10 conns; close()             returns to pool; bounded borrow = backpressure). Schema: accounts (kinds             customer/external/credit/loan; a currency per account; NUMERIC(20,8); CHECK constraints             enforce card floor -1000, loan floor -100000), transactions (txId primary key = idempotency             gate), entries (double-entry, sums to zero per currency per transaction, append-only by             trigger), outbox. In-region payment: ONE ACID transaction, ordered FOR UPDATE locks             (ascending id · deadlock impossible by construction). Cross-region: depart (payer ->             in_transit clearing account + outbox event, same commit) -> relay (virtual thread, producer             acks=all, marked published only after broker ack = at-least-once) -> Kafka 3.8 KRaft, topic             payments -> applier (group shard-applier) arrives idempotently, gated by the same txId on             the destination's own transactions table; missing destination -> deterministic refund.             Fleet-wide SUM(in_transit) = money in flight, zero when drained. Notifications: its own             consumer group and own database, event_key primary key dedupe. Trades: one transaction, four             entries, two currencies, against per-region broker accounts; prices CoinGecko and Yahoo in USD, settled in EUR x             the USD to EUR rate from the FX SERVICE (its own process on :8090, called with a 500ms deadline and a fallback rate), 60s cache, locked at execution. Relocation: new account in the target region,             moving flag pauses writes for milliseconds, the balance travels as one standard cross-region             payment, then the directory pointer flips. One Docker image; k8s manifests scale ONLY the             stateless app tier and pin each region's database to its geography. 38 executable tests             prove each mechanism. Products: savings, credit card (limit 1000; hold/capture/release is             real card authorization), bitcoin and Apple stock, personal loan (to 20000; disbursement             leaves net worth unchanged).

            CAST (name=id (region)): %s
            THIS CUSTOMER: %s, id %s, region %s. Balances: main €%s, savings €%s, card €%s (held €%s), \
            bitcoin %s BTC (price €%s), apple %s shares (price €%s), loan €%s. In flight fleet-wide: €%s. \
            Recent rows (time kind amount counterparty): %s

            Conversation so far (may be empty): %s""".formatted(
                cast.toString(), owner, customerId, region,
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

    /** find `"key":` and walk the JSON STRING that follows, unescaping ·
     *  a tiny honest parser instead of a JSON library. Returns null when
     *  the value is not a string (e.g. "content":null on tool calls). */
    static String extractString(String json, String key) {
        int i = json.indexOf('"' + key + '"');
        if (i < 0) return null;
        int c = json.indexOf(':', i + key.length());
        if (c < 0) return null;
        int start = c + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length() || json.charAt(start) != '"') return null;
        StringBuilder sb = new StringBuilder();
        for (int q = start + 1; q < json.length(); q++) {
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
