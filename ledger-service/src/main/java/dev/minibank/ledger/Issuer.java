package dev.minibank.ledger;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
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
        Existing prior = load(authorizationId);
        if (prior != null) {
            // the same authorisation asked for again. Answer identically and do
            // nothing, whatever the acquirer's retry logic believes.
            return new Decision(authorizationId, "approved".equals(prior.state()) || "captured".equals(prior.state()),
                    prior.reason() == null ? "replay" : prior.reason());
        }

        if (amount == null || amount.signum() <= 0) {
            return record(authorizationId, token, 0, amount, currency, "declined", "amount must be positive", businessAt);
        }

        long customerId;
        try {
            customerId = cardholder(token);
        } catch (UnknownInstrument e) {
            // a frozen or cancelled card lands here too, and the acquirer is
            // told only that it was declined
            return record(authorizationId, token, 0, amount, currency, "declined", "instrument not usable", businessAt);
        }

        Ledger.TransferResult result = Products.authorize(authorizationId, customerId, amount);
        boolean approved = result instanceof Ledger.Ok || result instanceof Ledger.AlreadyProcessed;
        String reason = approved ? null
                : result instanceof Ledger.InsufficientFunds ? "insufficient credit"
                : "card unavailable";
        return record(authorizationId, token, customerId, amount, currency,
                approved ? "approved" : "declined", reason, businessAt);
    }

    /** Take the money that was held. */
    public static boolean capture(UUID authorizationId, Instant businessAt) throws SQLException {
        Existing a = load(authorizationId);
        if (a == null) return false;
        if ("captured".equals(a.state())) return true;      // idempotent
        if (!"approved".equals(a.state())) return false;     // declined or voided: nothing to take

        Ledger.TransferResult r = Products.capture(authorizationId, a.customerId());
        boolean ok = r instanceof Ledger.Ok || r instanceof Ledger.AlreadyProcessed;
        if (ok) settle(authorizationId, "captured", businessAt);
        return ok;
    }

    /** Give the hold back. The order fell through, and a customer must not have
     *  their limit eaten by a purchase that never happened. */
    public static boolean voidAuthorization(UUID authorizationId, Instant businessAt) throws SQLException {
        Existing a = load(authorizationId);
        if (a == null) return false;
        if ("voided".equals(a.state())) return true;         // idempotent
        if (!"approved".equals(a.state())) return false;

        Ledger.TransferResult r = Products.release(authorizationId, a.customerId());
        boolean ok = r instanceof Ledger.Ok || r instanceof Ledger.AlreadyProcessed;
        if (ok) settle(authorizationId, "voided", businessAt);
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

    private record Existing(String state, String reason, long customerId) {}

    private static Existing load(UUID id) throws SQLException {
        try (Connection c = Directory.openForRead();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT state, reason, customer_id FROM card_authorizations WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Existing(rs.getString(1), rs.getString(2), rs.getLong(3)) : null;
            }
        }
    }

    private static Decision record(UUID id, String token, long customerId, BigDecimal amount,
                                   String currency, String state, String reason, Instant businessAt)
            throws SQLException {
        try (Connection c = Directory.openForRead();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO card_authorizations(id, token, customer_id, amount, currency, state, reason, business_at)
                     VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (id) DO NOTHING""")) {
            ps.setObject(1, id); ps.setString(2, token); ps.setLong(3, customerId);
            ps.setBigDecimal(4, amount == null ? BigDecimal.ZERO : amount);
            ps.setString(5, currency == null ? "EUR" : currency);
            ps.setString(6, state); ps.setString(7, reason);
            ps.setTimestamp(8, java.sql.Timestamp.from(businessAt));
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
}
