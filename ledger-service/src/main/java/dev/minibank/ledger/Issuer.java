package dev.minibank.ledger;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * THIS BANK, ACTING AS A CARD ISSUER.
 *
 * A merchant somewhere else takes an order. Its processor asks us whether the
 * money is good. We approve or decline against a credit limit we own, hold the
 * amount, and later capture it or give it back. That is the issuer's whole job,
 * and it makes this bank one corner of the four-party model: minimart is the
 * merchant, minipay is the acquirer, minibank is the issuer, and a customer
 * here is the cardholder.
 *
 * ALMOST NONE OF THIS IS NEW WORK, and that is the point worth noticing. The
 * card already had authorize, capture and release, already enforced its limit
 * with a CHECK constraint rather than an if-statement, and already made retries
 * harmless with derived transaction ids. Becoming an issuer needed a way to
 * refer to a card from outside, and nothing else. A mechanism that was built
 * once for the bank's own screen turned out to be the mechanism a payment
 * network needs, because it was the right mechanism.
 *
 * WHAT THE ACQUIRER MAY LEARN, precisely:
 *   approved, or declined with a reason,
 *   and for a lookup: the brand label, the last four digits, and the status.
 * It may NOT learn the customer id, the region, the balance, the limit, or the
 * available credit. An acquirer that could compute availability would
 * eventually pre-check it, race the constraint, and reintroduce the
 * read-then-decide bug the CHECK constraint exists to make impossible.
 */
public final class Issuer {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** What the acquirer gets back. Never more than this. */
    public record Decision(UUID authorizationId, boolean approved, String reason) {}

    /** What a merchant may safely put on a receipt. */
    public record Instrument(String token, String brandLabel, String last4, String status) {}

    public static class UnknownInstrument extends RuntimeException {
        public UnknownInstrument(String token) { super("no such instrument: " + token); }
    }

    private Issuer() {}

    // ------------------------------------------------------------------ cards

    /**
     * Issue a card to a customer, or return the one they already hold.
     *
     * Idempotent because a customer pressing "get a card" twice should end up
     * with one card, and because the unique index would otherwise turn a double
     * click into an error page.
     */
    public static Instrument issueCard(long customerId) throws SQLException {
        Instrument existing = cardOf(customerId);
        if (existing != null) return existing;

        // 24 hex characters of real randomness. Not derived from the customer
        // id: a token you can compute from something you already know is not a
        // token, it is a formatting convention.
        byte[] raw = new byte[12];
        RANDOM.nextBytes(raw);
        StringBuilder b = new StringBuilder("mbc_");
        for (byte x : raw) b.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        String token = b.toString();
        String last4 = String.format("%04d", Math.floorMod(token.hashCode(), 10000));

        try (Connection c = Directory.openForRead();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO card_instruments(token, customer_id, last4)
                     VALUES (?,?,?) ON CONFLICT DO NOTHING""")) {
            ps.setString(1, token); ps.setLong(2, customerId); ps.setString(3, last4);
            ps.executeUpdate();
        }
        Instrument issued = cardOf(customerId);
        return issued != null ? issued : new Instrument(token, "minibank credit", last4, "active");
    }

    public static Instrument cardOf(long customerId) throws SQLException {
        try (Connection c = Directory.openForRead();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT token, brand_label, last4, status FROM card_instruments
                      WHERE customer_id = ? AND status <> 'cancelled'""")) {
            ps.setLong(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Instrument(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)) : null;
            }
        }
    }

    /** The only lookup an acquirer may make. Deliberately carries nothing
     *  financial: no balance, no limit, and above all no available credit. */
    public static Instrument describe(String token) throws SQLException {
        try (Connection c = Directory.openForRead();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT token, brand_label, last4, status FROM card_instruments WHERE token = ?""")) {
            ps.setString(1, token);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new UnknownInstrument(token);
                return new Instrument(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4));
            }
        }
    }

    private static long cardholder(String token) throws SQLException {
        try (Connection c = Directory.openForRead();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT customer_id FROM card_instruments WHERE token = ? AND status = 'active'")) {
            ps.setString(1, token);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new UnknownInstrument(token);
                return rs.getLong(1);
            }
        }
    }

    // --------------------------------------------------------- authorisation

    /**
     * Approve or decline, and hold the money if approved.
     *
     * The authorisation id is minted by the ACQUIRER and carried through, so a
     * retried authorisation is the same authorisation rather than a second hold
     * against the same customer for the same purchase. A network that retries
     * is normal; a bank that holds twice because of it is not.
     *
     * Note what decides the answer: the ledger's own CHECK constraint. Nothing
     * here reads a balance and compares it, because a read-then-decide would be
     * wrong under concurrency in exactly the way a credit limit must never be.
     */
    public static Decision authorize(UUID authorizationId, String token, BigDecimal amount,
                                      String currency, Instant businessAt) throws SQLException {
        return authorize(authorizationId, token, amount, currency, "card", businessAt);
    }

    /** As authorize, naming the merchant whose shop is taking the money. The
     *  cardholder's own activity view reads this back; an acquirer never sees it. */
    public static Decision authorize(UUID authorizationId, String token, BigDecimal amount,
                                      String currency, String merchant, Instant businessAt) throws SQLException {
        Existing prior = load(authorizationId);
        if (prior != null) {
            // THE SAME REFERENCE MUST MEAN THE SAME MONEY.
            //
            // The first version answered from the state alone and never looked
            // at the amount, so a retry of the same reference for a LARGER sum
            // was approved against the smaller hold that actually existed. The
            // difference was authorised by nobody and would have been captured
            // by an acquirer that believed it had asked for it.
            boolean sameMoney = prior.amount() != null && amount != null
                    && prior.amount().compareTo(amount) == 0
                    && java.util.Objects.equals(prior.currency(), currency == null ? "EUR" : currency);
            if (!sameMoney) {
                return new Decision(authorizationId, false,
                        "authorization reference reused for a different amount");
            }
            // the same authorisation asked for again. Answer identically and do
            // nothing, whatever the acquirer's retry logic believes.
            return new Decision(authorizationId, "approved".equals(prior.state()) || "captured".equals(prior.state()),
                    prior.reason() == null ? "replay" : prior.reason());
        }

        if (amount == null || amount.signum() <= 0) {
            return record(authorizationId, token, 0, amount, currency, "declined", "amount must be positive", merchant, businessAt);
        }

        long customerId;
        try {
            customerId = cardholder(token);
        } catch (UnknownInstrument e) {
            // a frozen or cancelled card lands here too, and the acquirer is
            // told only that it was declined
            return record(authorizationId, token, 0, amount, currency, "declined", "instrument not usable", merchant, businessAt);
        }

        Ledger.TransferResult result = Products.authorize(authorizationId, customerId, amount);
        boolean approved = result instanceof Ledger.Ok || result instanceof Ledger.AlreadyProcessed;
        String reason = approved ? null
                : result instanceof Ledger.InsufficientFunds ? "insufficient credit"
                : "card unavailable";
        // The card rail is the acquirer's traffic, and it arrives over HTTP from
        // minipay rather than from anyone using this bank's own screens, so it
        // was entirely absent from the money graph. A decline is counted too:
        // an issuer that declines is working, and a graph that only shows
        // approvals cannot tell a quiet night from a broken limit check.
        Metrics.inc("minibank_ledger_events_total",
                approved ? "kind=\"card_authorized\"" : "kind=\"card_declined\"");
        return record(authorizationId, token, customerId, amount, currency,
                approved ? "approved" : "declined", reason, merchant, businessAt);
    }

    /** Take the money that was held. */
    public static boolean capture(UUID authorizationId, Instant businessAt) throws SQLException {
        Existing a = load(authorizationId);
        if (a == null) return false;
        if ("captured".equals(a.state())) return true;      // idempotent
        if (!"approved".equals(a.state())) return false;     // declined or voided: nothing to take

        // Claim the transition FIRST. Whoever wins the compare-and-swap is the
        // one that moves the money, so a capture and a void racing each other
        // cannot both succeed.
        if (!claimState(authorizationId, "approved", "captured", businessAt)) {
            // somebody else took it: re-read and answer honestly
            Existing now = load(authorizationId);
            return now != null && "captured".equals(now.state());
        }
        Ledger.TransferResult r = Products.capture(authorizationId, a.customerId());
        boolean ok = r instanceof Ledger.Ok || r instanceof Ledger.AlreadyProcessed;
        // the ledger refused, so the state must go back rather than claim a
        // capture that never moved anything
        if (!ok) claimState(authorizationId, "captured", "approved", businessAt);
        else Metrics.inc("minibank_ledger_events_total", "kind=\"card_captured\"");
        return ok;
    }

    /** Give the hold back. The order fell through, and a customer must not have
     *  their limit eaten by a purchase that never happened. */
    public static boolean voidAuthorization(UUID authorizationId, Instant businessAt) throws SQLException {
        Existing a = load(authorizationId);
        if (a == null) return false;
        if ("voided".equals(a.state())) return true;         // idempotent
        if (!"approved".equals(a.state())) return false;

        if (!claimState(authorizationId, "approved", "voided", businessAt)) {
            Existing now = load(authorizationId);
            return now != null && "voided".equals(now.state());
        }
        Ledger.TransferResult r = Products.release(authorizationId, a.customerId());
        boolean ok = r instanceof Ledger.Ok || r instanceof Ledger.AlreadyProcessed;
        if (!ok) claimState(authorizationId, "voided", "approved", businessAt);
        else Metrics.inc("minibank_ledger_events_total", "kind=\"card_released\"");
        return ok;
    }

    /**
     * AUDIT · authorisations approved and never resolved.
     *
     * Every one of these is a customer's credit limit being consumed by a
     * purchase that may never have completed. In a real network these age out;
     * here they are simply listed, because the point is that the question can be
     * asked at all. A hold nobody can enumerate is a hold nobody will find.
     */
    public static long outstandingHolds() throws SQLException {
        try (Connection c = Directory.openForRead();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM card_authorizations WHERE state = 'approved'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ------------------------------------------------------------- internals

    /** A line on the cardholder's own statement: whose shop, how much, what
     *  became of it, which card. Everything an acquirer must NOT see is absent. */
    public record Activity(UUID id, String merchant, BigDecimal amount, String currency,
                           String state, String last4, Instant businessAt) {}

    /**
     * THE CARDHOLDER'S OWN QUESTION · "what happened on my card", newest first.
     *
     * This is the surface the 2026-07-23 break exposed as missing: the mart
     * charge was authorized and captured perfectly, and the customer saw
     * nothing anywhere. The money was on the books; only the QUESTION was.
     */
    public static List<Activity> activity(long customerId, int limit) throws SQLException {
        try (Connection c = Directory.openForRead();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT a.id, a.merchant, a.amount, a.currency, a.state, i.last4, a.business_at
                     FROM card_authorizations a
                     JOIN card_instruments i ON i.token = a.token
                     WHERE a.customer_id = ?
                     ORDER BY a.business_at DESC, a.created_at DESC
                     LIMIT ?""")) {
            ps.setLong(1, customerId);
            ps.setInt(2, Math.max(1, Math.min(limit, 100)));
            List<Activity> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    out.add(new Activity((UUID) rs.getObject(1), rs.getString(2), rs.getBigDecimal(3),
                            rs.getString(4), rs.getString(5), rs.getString(6), rs.getTimestamp(7).toInstant()));
            }
            return out;
        }
    }

    private record Existing(String state, String reason, long customerId,
                            BigDecimal amount, String currency) {}

    private static Existing load(UUID id) throws SQLException {
        try (Connection c = Directory.openForRead();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT state, reason, customer_id, amount, currency FROM card_authorizations WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Existing(rs.getString(1), rs.getString(2), rs.getLong(3),
                        rs.getBigDecimal(4), rs.getString(5)) : null;
            }
        }
    }

    /**
     * Move an authorisation to its next state, and say whether THIS caller is
     * the one that moved it.
     *
     * A compare-and-swap rather than a read followed by a write. The first
     * version loaded the row on one connection, acted on the ledger, and wrote
     * the new state on a third, so a capture and a void arriving together could
     * both read "approved" and both proceed: the merchant would be paid AND the
     * cardholder's limit returned, for the same money, funded out of another of
     * that customer's holds. The next purchase of theirs would then be wedged
     * for reasons nobody could trace back here.
     */
    private static boolean claimState(UUID id, String from, String to, Instant at) throws SQLException {
        try (Connection c = Directory.openForRead();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE card_authorizations SET state = ?, settled_at = ? WHERE id = ? AND state = ?")) {
            ps.setString(1, to); ps.setTimestamp(2, java.sql.Timestamp.from(at));
            ps.setObject(3, id); ps.setString(4, from);
            return ps.executeUpdate() == 1;
        }
    }

    private static Decision record(UUID id, String token, long customerId, BigDecimal amount,
                                   String currency, String state, String reason, String merchant,
                                   Instant businessAt)
            throws SQLException {
        try (Connection c = Directory.openForRead();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO card_authorizations(id, token, customer_id, amount, currency, state, reason, merchant, business_at)
                     VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (id) DO NOTHING""")) {
            ps.setObject(1, id); ps.setString(2, token); ps.setLong(3, customerId);
            ps.setBigDecimal(4, amount == null ? BigDecimal.ZERO : amount);
            ps.setString(5, currency == null ? "EUR" : currency);
            ps.setString(6, state); ps.setString(7, reason);
            ps.setString(8, merchant == null || merchant.isBlank() ? "card" : merchant);
            ps.setTimestamp(9, java.sql.Timestamp.from(businessAt));
            ps.executeUpdate();
        }
        return new Decision(id, "approved".equals(state), reason);
    }

    private static void settle(UUID id, String state, Instant at) throws SQLException {
        try (Connection c = Directory.openForRead();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE card_authorizations SET state = ?, settled_at = ? WHERE id = ?")) {
            ps.setString(1, state); ps.setTimestamp(2, java.sql.Timestamp.from(at)); ps.setObject(3, id);
            ps.executeUpdate();
        }
    }

    // -------------------------------------------------------------- clearing

    /** What this issuer worked out for itself about a batch an acquirer sent. */
    public record Cleared(String batchId, BigDecimal settledGross, BigDecimal interchange,
                          BigDecimal settledNet, int matched, int unmatched) {}

    /**
     * The issuer's interchange: what it keeps for having lent the customer the
     * money and carried the risk. A real rate is a matrix of card type,
     * merchant category and geography, and one number here is a deliberate
     * simplification worth naming rather than implying interchange is simple.
     */
    public static volatile BigDecimal interchangeRate = new BigDecimal("0.008");

    /**
     * Accept a clearing batch, and WORK OUT THE TOTAL INDEPENDENTLY.
     *
     * The acquirer sends what it believes it is owed. This does not use that
     * number for anything except recording what was claimed. The value of a
     * clearing message is that two organisations sharing no database arrive at
     * the same figure on their own and then compare, and an issuer that adopted
     * the acquirer's total would make every reconciliation pass and mean
     * nothing. The disagreement is the signal.
     *
     * A line that matches no authorisation this bank is carrying is counted as
     * UNMATCHED rather than rejected or ignored. Rejecting the batch would
     * punish a hundred good lines for one bad one; ignoring it would hide the
     * only evidence of a dispute.
     *
     * Idempotent by the batch id and by a primary key on each authorisation, so
     * an acquirer that resends is not paid twice.
     */
    public static Cleared clear(String batchId, String currency, java.time.LocalDate businessDate,
                                BigDecimal claimedGross, BigDecimal claimedNet,
                                java.util.List<java.util.Map.Entry<UUID, BigDecimal>> lines,
                                Instant businessAt) throws SQLException {
        Cleared existing = clearedBatch(batchId);
        if (existing != null) return existing;    // a resend is the same batch

        BigDecimal settledGross = BigDecimal.ZERO, interchange = BigDecimal.ZERO;
        int matched = 0, unmatched = 0;
        java.util.List<Object[]> rows = new java.util.ArrayList<>();

        for (var line : lines) {
            Existing auth = load(line.getKey());
            // A line clears only if this bank is actually carrying that
            // authorisation and the amount agrees with what it approved. An
            // acquirer clearing more than was authorised is the oldest trick in
            // the business and it is caught here, on the issuer's own record.
            boolean ok = auth != null
                    && ("captured".equals(auth.state()) || "approved".equals(auth.state()))
                    && auth.amount() != null && auth.amount().compareTo(line.getValue()) == 0;
            BigDecimal fee = ok
                    ? line.getValue().multiply(interchangeRate).setScale(2, java.math.RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            if (ok) {
                settledGross = settledGross.add(line.getValue());
                interchange = interchange.add(fee);
                matched++;
            } else {
                unmatched++;
            }
            rows.add(new Object[]{line.getKey(), line.getValue(), fee, ok});
        }
        BigDecimal settledNet = settledGross.subtract(interchange);

        try (Connection c = Directory.openForRead()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO clearing_batches(id, currency, business_date, claimed_gross, claimed_net,
                                                     settled_gross, interchange, settled_net, matched, unmatched,
                                                     business_at)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (id) DO NOTHING""")) {
                    ps.setString(1, batchId); ps.setString(2, currency); ps.setObject(3, businessDate);
                    ps.setBigDecimal(4, claimedGross); ps.setBigDecimal(5, claimedNet);
                    ps.setBigDecimal(6, settledGross); ps.setBigDecimal(7, interchange);
                    ps.setBigDecimal(8, settledNet);
                    ps.setInt(9, matched); ps.setInt(10, unmatched);
                    ps.setTimestamp(11, java.sql.Timestamp.from(businessAt));
                    if (ps.executeUpdate() == 0) { c.rollback(); return clearedBatch(batchId); }
                }
                for (Object[] r : rows) {
                    try (PreparedStatement ps = c.prepareStatement("""
                            INSERT INTO clearing_lines(authorization_id, batch_id, amount, interchange, matched)
                            VALUES (?,?,?,?,?) ON CONFLICT (authorization_id) DO NOTHING""")) {
                        ps.setObject(1, r[0]); ps.setString(2, batchId);
                        ps.setBigDecimal(3, (BigDecimal) r[1]); ps.setBigDecimal(4, (BigDecimal) r[2]);
                        ps.setBoolean(5, (Boolean) r[3]);
                        ps.executeUpdate();
                    }
                }
                c.commit();
            } catch (SQLException | RuntimeException e) { c.rollback(); throw e; }
        }
        return new Cleared(batchId, settledGross, interchange, settledNet, matched, unmatched);
    }

    public static Cleared clearedBatch(String batchId) throws SQLException {
        try (Connection c = Directory.openForRead();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT settled_gross, interchange, settled_net, matched, unmatched
                       FROM clearing_batches WHERE id = ?""")) {
            ps.setString(1, batchId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Cleared(batchId, rs.getBigDecimal(1), rs.getBigDecimal(2),
                        rs.getBigDecimal(3), rs.getInt(4), rs.getInt(5));
            }
        }
    }
}
