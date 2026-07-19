package dev.minibank.broker;

import dev.minibank.ledger.Shard;
import dev.minibank.ledger.Shards;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * THE THIRD AUDIT · do the two books agree about what the customer owns?
 *
 * The bank already runs two invariants continuously. Sum-zero says every
 * transaction's entries balance, per currency. Drift says every cached
 * balance equals the sum of its entries. Both are INSIDE the ledger, and
 * that is their limit: they were green on every single trade that made the
 * two books disagree, because a ledger with a holding the broker never heard
 * of is a perfectly consistent ledger. An invariant that only looks at one
 * book cannot see a book being skipped.
 *
 * THE INVARIANT, stated as an equation:
 *
 *     ledger asset balance == broker position qty - in-flight qty
 *
 * for every (customer, symbol). The broker owns the position; the ledger
 * owns custody of the asset as a double-entry consequence of settlement.
 * When the pipeline is the only way in, those two numbers are the same fact
 * recorded twice, and they must agree.
 *
 * THE IN-FLIGHT TERM IS NOT A TOLERANCE. A fill moves the broker's position
 * in the same commit that writes the order.filled event; the ledger moves
 * when it consumes that event. Between those two moments the broker is
 * legitimately AHEAD, and subtracting an explicitly measured bucket is very
 * different from allowing a fuzzy margin. Both columns are numeric(20,8) and
 * the venue's quantity is passed through verbatim rather than recomputed, so
 * exact equality is the correct comparison; a numeric tolerance here would
 * hide precisely the class of bug this exists to catch.
 *
 * NOTE THE SIGN, because the two directions mean opposite things.
 *   ledger > broker  a holding exists that the broker never saw · something
 *                    wrote to the ledger outside the order pipeline.
 *   broker > ledger  beyond in-flight, the broker credited a position the
 *                    ledger never funded. Rarer and worse.
 * A single unsigned "they differ" number would blur the two together.
 *
 * WHY THIS CLASS OPENS BOTH DATABASES, when nothing else in this bank does.
 * It is the AUDITOR, and reading both books is its entire job · an audit that
 * has to ask one of the parties for the other party's numbers is not an
 * audit. What keeps that from dissolving the service boundary is that this
 * class is strictly READ-ONLY: it never writes to either database, it is
 * never on a request's money path, and no service uses it to answer a
 * question about its own domain. It reports. Repair is a separate, deliberate
 * act ({@link Backfill}), and it goes through the broker's own tables.
 */
public final class Reconciliation {

    /**
     * One (customer, symbol) where the books do not line up.
     *
     * ledgerQty is null when the ledger has a mapping row pointing at an
     * account that does not exist · an absent account is not a zero balance,
     * it is a broken pointer, and the two must never be reported as the same
     * thing.
     */
    public record Divergence(long customerId, String symbol, BigDecimal ledgerQty,
                             BigDecimal brokerQty, BigDecimal inFlight, String note) {

        /** ledger minus what the broker says the ledger should be holding. */
        public BigDecimal gap() {
            if (ledgerQty == null) return null;
            return ledgerQty.subtract(brokerQty.subtract(inFlight));
        }

        @Override
        public String toString() {
            if (ledgerQty == null)
                return customerId + "/" + symbol + ": " + note;
            String g = gap().signum() > 0 ? "+" + gap().toPlainString() : gap().toPlainString();
            return customerId + "/" + symbol
                    + ": ledger " + ledgerQty.toPlainString()
                    + " broker " + brokerQty.toPlainString()
                    + (inFlight.signum() == 0 ? "" : " in-flight " + inFlight.toPlainString())
                    + " gap " + g
                    + (note == null ? "" : " · " + note);
        }
    }

    private Reconciliation() {}

    /**
     * Every (customer, symbol) whose two books disagree. Empty is the only
     * healthy answer.
     *
     * Reads three databases at three instants, so a divergence seen once may
     * be ordinary read skew. Callers that alarm on this should re-check a
     * divergence before paging · what must never be done is to widen the
     * comparison until the skew fits inside it.
     */
    public static List<Divergence> divergences() throws SQLException {
        return divergences(SETTLEMENT_GRACE);
    }

    /**
     * HOW LONG A FILL IS ALLOWED TO BE IN FLIGHT before its gap stops being
     * excused and starts being reported.
     *
     * The saga's happy path is a Kafka round trip between two local
     * transactions · milliseconds when nothing is wrong, seconds when
     * something is retrying. Five minutes is far past either, so anything
     * still in flight at that age is not a window, it is a stall.
     */
    public static final java.time.Duration SETTLEMENT_GRACE = java.time.Duration.ofMinutes(5);

    /**
     * The same audit with an explicit grace, so a test can ask what the books
     * look like once the window has closed without waiting for it to.
     */
    public static List<Divergence> divergences(java.time.Duration grace) throws SQLException {
        Map<String, BigDecimal> ledger = ledgerHoldings();
        Map<String, BigDecimal> broker = brokerPositions();
        Map<String, BigDecimal> inFlight = inFlightQuantities(grace);
        Map<String, String> notes = new LinkedHashMap<>();

        // the union of both books · a holding present in only one of them is
        // the most important row this audit can produce, so iterating either
        // one alone would defeat the purpose
        Map<String, Boolean> keys = new LinkedHashMap<>();
        for (String k : ledger.keySet()) keys.put(k, true);
        for (String k : broker.keySet()) keys.put(k, true);
        for (String k : inFlight.keySet()) keys.put(k, true);
        notes.putAll(danglingLedgerAccounts());
        for (String k : notes.keySet()) keys.put(k, true);

        List<Divergence> out = new ArrayList<>();
        for (String key : keys.keySet()) {
            long customerId = Long.parseLong(key.substring(0, key.indexOf('/')));
            String symbol = key.substring(key.indexOf('/') + 1);
            BigDecimal l = ledger.get(key);
            BigDecimal b = broker.getOrDefault(key, BigDecimal.ZERO);
            BigDecimal f = inFlight.getOrDefault(key, BigDecimal.ZERO);
            String note = notes.get(key);

            if (note != null) {                    // broken pointer · always reported
                out.add(new Divergence(customerId, symbol, null, b, f, note));
                continue;
            }
            BigDecimal have = l == null ? BigDecimal.ZERO : l;
            BigDecimal expect = b.subtract(f);
            if (have.compareTo(expect) != 0)
                out.add(new Divergence(customerId, symbol, have, b, f, null));
        }
        return out;
    }

    // ------------------------------------------------------------------ ledger side

    /**
     * What the LEDGER says each customer holds, across every shard.
     *
     * LEFT JOIN, and that is load-bearing. asset_accounts.account_id has a
     * UNIQUE constraint but no foreign key to accounts, so a mapping row can
     * outlive the account it points at (a truncated demo database does it in
     * one statement). An INNER JOIN would silently drop those rows and the
     * audit would under-report the ledger side · which is a way for a real
     * holding to become invisible to the very check meant to catch it. The
     * ledger already refuses this confusion elsewhere: an absent account is
     * NOT a zero balance.
     */
    private static Map<String, BigDecimal> ledgerHoldings() throws SQLException {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Shard s : Shards.all()) {
            try (Connection c = s.open();
                 PreparedStatement ps = c.prepareStatement("""
                         SELECT aa.customer_id, aa.symbol, a.balance
                         FROM asset_accounts aa
                         LEFT JOIN accounts a ON a.id = aa.account_id
                         ORDER BY aa.customer_id, aa.symbol""");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    BigDecimal bal = rs.getBigDecimal(3);
                    if (bal == null) continue;           // reported by danglingLedgerAccounts
                    out.merge(rs.getLong(1) + "/" + rs.getString(2), bal, BigDecimal::add);
                }
            }
        }
        return out;
    }

    /** Mapping rows pointing at accounts that do not exist · a broken pointer
     *  is not a zero, and it is not a rounding question either. */
    private static Map<String, String> danglingLedgerAccounts() throws SQLException {
        Map<String, String> out = new LinkedHashMap<>();
        for (Shard s : Shards.all()) {
            try (Connection c = s.open();
                 PreparedStatement ps = c.prepareStatement("""
                         SELECT aa.customer_id, aa.symbol, aa.account_id
                         FROM asset_accounts aa
                         LEFT JOIN accounts a ON a.id = aa.account_id
                         WHERE a.id IS NULL""");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    out.put(rs.getLong(1) + "/" + rs.getString(2),
                            "the ledger maps this holding to account " + rs.getLong(3)
                            + ", which does not exist on " + Shards.regionName(s.index));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ broker side

    private static Map<String, BigDecimal> brokerPositions() throws SQLException {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        try (Connection c = BrokerDb.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT customer_id, symbol, qty FROM positions ORDER BY customer_id, symbol");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.put(rs.getLong(1) + "/" + rs.getString(2), rs.getBigDecimal(3));
        }
        return out;
    }

    /**
     * THE LEGITIMATE WINDOW · a fill the broker has booked and the ledger has
     * not settled yet.
     *
     * A fill moves the position in the same commit that writes the
     * order.filled event; the ledger moves when it consumes that event. In
     * between, the broker is legitimately ahead by exactly this quantity.
     *
     * THE OBVIOUS DEFINITION IS WRONG, AND IT IS WRONG SILENTLY. "Every fill
     * whose order is still status='filled'" looks right and produces a FALSE
     * POSITIVE on every single settlement, because the window has two halves
     * and only the first one is a real gap:
     *
     *   ledger has not settled  broker q, ledger 0, order 'filled'  gap q
     *   ledger HAS settled      broker q, ledger q, order 'filled'  gap 0
     *
     * The order sits in 'filled' through BOTH halves · it becomes 'settled'
     * only when the ledger's answer completes the round trip back over Kafka.
     * Subtracting q during the second half would report a divergence of -q on
     * a pair of books that agree perfectly, which is the kind of alarm that
     * gets muted and then ignored on the day it is real.
     *
     * So the question is asked of the LEDGER instead: has this fill id been
     * settled? The ledger reuses the fill id verbatim as its txId, so the
     * answer is a primary-key lookup in transactions. That is a question only
     * the auditor can ask · the broker cannot see the ledger's transactions
     * and the ledger cannot see the broker's fills · and being able to ask it
     * is the whole reason this class holds both books.
     *
     * It covers the REJECTION path for free. A refused settlement is recorded
     * with kind 'settle-refused', which does not match the 'settle:%' pattern
     * below, so a refused fill correctly still counts as in-flight · and it
     * stays that way exactly until the compensation moves the order to
     * 'rejected' and takes the position back. The window that legitimises the
     * gap is the same window that closes it.
     *
     * AND IT IS BOUNDED, which is the half that was missing. "Filled but not
     * settled" with no floor is an exemption that renews itself forever: if
     * the compensation THROWS · Broker.compensate reverses a buy with
     * advance(p, "sell", qty), which refuses when the position no longer holds
     * those units · the order stays at 'filled' for good, its fill keeps
     * counting here, expect = broker - inFlight = 0 is compared against a
     * ledger of 0, and divergences() reports nothing at all. A position the
     * customer never paid for became invisible to the only check wired to a
     * metric on the healthy path. {@link #stalled} did know, but it is a
     * separate list nobody alarms on and it reads empty for the first five
     * minutes regardless.
     *
     * So the exemption expires. Past `grace`, a fill stops being in flight and
     * starts being a divergence, which is exactly what it is · the class
     * comment always said a legitimate gap is legitimate only while it is
     * YOUNG, and this is the line that finally makes that true.
     */
    private static Map<String, BigDecimal> inFlightQuantities(java.time.Duration grace) throws SQLException {
        Set<UUID> settled = ledgerSettledFillIds();
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        try (Connection c = BrokerDb.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT f.id, o.customer_id, o.symbol,
                            CASE WHEN (o.side = 'buy') <> (f.kind = 'compensation')
                                 THEN f.qty ELSE -f.qty END AS signed_qty
                     FROM fills f JOIN orders o ON o.id = f.order_id
                     WHERE o.status = 'filled'
                       AND o.updated_at >= now() - (? || ' seconds')::interval""")) {
            ps.setString(1, String.valueOf(grace.toSeconds()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (settled.contains(rs.getObject(1, UUID.class))) continue;   // money already moved
                    out.merge(rs.getLong(2) + "/" + rs.getString(3), rs.getBigDecimal(4), BigDecimal::add);
                }
            }
        }
        return out;
    }

    /** Which fills the ledger has already turned into money · the fill id IS
     *  the ledger's txId, which is what makes this a key lookup rather than a
     *  reconciliation of its own. */
    private static Set<UUID> ledgerSettledFillIds() throws SQLException {
        Set<UUID> out = new HashSet<>();
        for (Shard s : Shards.all()) {
            try (Connection c = s.open();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT id FROM transactions WHERE kind LIKE 'settle:%'");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getObject(1, UUID.class));
            }
        }
        return out;
    }

    /**
     * Orders that have been in-flight longer than they have any business
     * being · the age alarm the in-flight exemption needs to be safe.
     *
     * Without this, "filled but not settled" is an exemption with no floor:
     * a saga that stalls forever would excuse its own divergence forever, and
     * the audit would read green while a customer's books stayed wrong.
     */
    public static List<String> stalled(java.time.Duration olderThan) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = BrokerDb.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT id, customer_id, symbol, side, updated_at
                     FROM orders
                     WHERE status = 'filled' AND updated_at < now() - (? || ' seconds')::interval
                     ORDER BY updated_at""")) {
            ps.setString(1, String.valueOf(olderThan.toSeconds()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    out.add(rs.getObject(1) + " · " + rs.getLong(2) + "/" + rs.getString(3)
                            + " " + rs.getString(4) + " filled at " + rs.getTimestamp(5)
                            + " and never settled");
            }
        }
        return out;
    }
}
