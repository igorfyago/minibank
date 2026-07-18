package dev.minibank.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * PRODUCTS · the Revolut shelf, built the honest way: every product is
 * just ACCOUNTS plus DOUBLE-ENTRY TRANSFERS. That is not a demo shortcut;
 * it is how real banks build products.
 *
 *   SAVINGS   a second account. "Move to savings" = a transfer between
 *             your own accounts · same shard, plain ACID, nothing new.
 *   CARD      an account whose kind is 'credit': the schema lets it go
 *             negative to a floor (-1000). Spending = card -> café.
 *             The credit limit is a CHECK constraint, not an if-statement.
 *   CRYPTO /  a MULTI-CURRENCY ledger. Your BTC account is denominated in
 *   STOCKS    BTC; a buy is ONE transaction with four entries · EUR legs
 *             (you -> broker) and asset legs (broker -> you) · and each
 *             currency's legs sum to zero on their own. The audit becomes
 *             per-currency; the invariant holds in every unit at once.
 *   MORTGAGE  a 'loan' account. Disbursement: loan -A, main +A in one
 *             commit · your net position is unchanged, which is the
 *             honest accounting truth of borrowing money.
 *
 * Product accounts live at fixed offsets from the customer id and are
 * registered in the directory with the customer's home region, so every
 * product operation stays a LOCAL ACID transaction. (The offsets preserve
 * id parity, so even the arithmetic router keeps them home.)
 */
public final class Products {

    public static final long SAVINGS = 100, BTC = 200, AAPL = 300, CARD = 400, LOAN = 500, HOLDS = 600;
    /** every offset of the shelf, in id order · relocation walks this */
    public static final long[] OFFSETS = {SAVINGS, BTC, AAPL, CARD, LOAN, HOLDS};
    public static final BigDecimal MORTGAGE_CAP = new BigDecimal("20000.00");

    private Products() {}

    /** Idempotent: create the customer's product accounts on their home
     *  shard and point the directory at them. Safe to run every boot. */
    public static void ensureFor(long customerId) throws SQLException {
        ensureOn(Shards.forCustomer(customerId), customerId);
    }

    /** The same, on an EXPLICIT shard. Relocation needs this: mid-move the
     *  router refuses to answer for this customer (that is the write-pause),
     *  so the destination shard has to be named rather than looked up. */
    public static void ensureOn(Shard home, long customerId) throws SQLException {
        try (Connection c = home.open()) {
            ensure(c, customerId + SAVINGS, "savings", "customer", "EUR");
            ensure(c, customerId + BTC, "bitcoin", "customer", "BTC");
            ensure(c, customerId + AAPL, "apple stock", "customer", "AAPL");
            ensure(c, customerId + CARD, "card", "credit", "EUR");
            ensure(c, customerId + LOAN, "loan", "loan", "EUR");
            ensure(c, customerId + HOLDS, "card hold", "customer", "EUR");
            // rename migration: earlier builds labeled the account 'mortgage'
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE accounts SET owner = 'loan' WHERE id = ? AND owner = 'mortgage'")) {
                ps.setLong(1, customerId + LOAN);
                ps.executeUpdate();
            }
        }
        for (long off : OFFSETS) {
            try {
                Directory.register(customerId + off, label(off), home.index);
            } catch (Exception ignored) {
                // no directory in arithmetic-router contexts (lesson tests):
                // parity keeps the products home anyway
            }
        }
    }

    private static String label(long off) {
        return off == SAVINGS ? "savings" : off == BTC ? "bitcoin"
                : off == AAPL ? "apple stock" : off == CARD ? "card"
                : off == HOLDS ? "card hold" : "loan";
    }

    // ------------------------------------------------------------------
    // card authorization: the hold lifecycle, as transfers
    // ------------------------------------------------------------------
    // A card payment is TWO moments, not one. AUTHORIZE reserves the money
    // (card -> holds: the card's balance already carries the hold, so the
    // credit-limit CHECK constraint counts holds automatically). CAPTURE
    // moves the held money on to the merchant; RELEASE gives it back.
    // Every step is a plain gated transfer · capture and release use
    // DETERMINISTIC ids derived from the authorization, so each can happen
    // at most once no matter how the card network retries.

    public static Ledger.TransferResult authorize(UUID authTx, long customerId, BigDecimal amount) throws SQLException {
        return Shards.forCustomer(customerId)
                .transferLocal(authTx, customerId + CARD, customerId + HOLDS, amount);
    }

    public static Ledger.TransferResult capture(UUID authTx, long customerId) throws SQLException {
        BigDecimal amt = heldAmount(authTx, customerId);
        UUID captureId = UUID.nameUUIDFromBytes(("capture:" + authTx).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // capture-after-release finds the holds account short -> the funds
        // check answers InsufficientFunds: the double-spend dies politely
        return Shards.forCustomer(customerId)
                .transferLocal(captureId, customerId + HOLDS, Shard.CAFE, amt);
    }

    public static Ledger.TransferResult release(UUID authTx, long customerId) throws SQLException {
        BigDecimal amt = heldAmount(authTx, customerId);
        UUID releaseId = UUID.nameUUIDFromBytes(("release:" + authTx).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return Shards.forCustomer(customerId)
                .transferLocal(releaseId, customerId + HOLDS, customerId + CARD, amt);
    }

    /** the authorization's own entry says how much was held · the ledger is
     *  the source of truth for the hold, like for everything else */
    private static BigDecimal heldAmount(UUID authTx, long customerId) throws SQLException {
        Shard home = Shards.forCustomer(customerId);
        try (Connection c = home.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT amount FROM entries WHERE tx_id = ? AND account_id = ? AND amount > 0")) {
            ps.setObject(1, authTx);
            ps.setLong(2, customerId + HOLDS);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("no such authorization: " + authTx);
                return rs.getBigDecimal(1);
            }
        }
    }

    private static void ensure(Connection c, long id, String owner, String kind, String currency) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO accounts(id, owner, balance, version, kind, currency) VALUES (?,?,0,0,?,?) ON CONFLICT (id) DO NOTHING")) {
            ps.setLong(1, id);
            ps.setString(2, owner);
            ps.setString(3, kind);
            ps.setString(4, currency);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------
    // the trade: one transaction, two currencies, four entries
    // ------------------------------------------------------------------
    public static Ledger.TransferResult trade(UUID txId, long customerId, String asset,
                                              boolean buy, BigDecimal eur, BigDecimal price) throws SQLException {
        if (eur.signum() <= 0 || price.signum() <= 0) throw new IllegalArgumentException("amount and price must be positive");
        long assetAcct = customerId + ("btc".equals(asset) ? BTC : AAPL);
        long brokerAsset = "btc".equals(asset) ? Shard.BROKER_BTC : Shard.BROKER_AAPL;
        BigDecimal units = eur.divide(price, 8, RoundingMode.HALF_UP);
        if (units.signum() == 0) throw new IllegalArgumentException("amount too small at this price");

        Shard home = Shards.forCustomer(customerId);
        try (Connection conn = home.open()) {
            conn.setAutoCommit(false);
            try {
                if (!Ledger.claimTx(conn, txId, "trade:" + asset + ":" + (buy ? "buy" : "sell"))) {
                    conn.rollback();
                    return new Ledger.AlreadyProcessed();
                }
                // ordered locking across all four rows · same global rule
                long[] ids = {Shard.BROKER_EUR, brokerAsset, customerId, assetAcct};
                java.util.Arrays.sort(ids);
                Ledger.Account main = null, assetA = null;
                for (long lid : ids) {
                    Ledger.Account a = Ledger.lockAccount(conn, lid);
                    if (lid == customerId) main = a;
                    if (lid == assetAcct) assetA = a;
                }
                if (buy && main.balance().compareTo(eur) < 0) { conn.rollback(); return new Ledger.InsufficientFunds(); }
                if (!buy && assetA.balance().compareTo(units) < 0) { conn.rollback(); return new Ledger.InsufficientFunds(); }

                BigDecimal se = buy ? eur.negate() : eur;        // customer's EUR leg
                BigDecimal su = buy ? units : units.negate();    // customer's asset leg
                Ledger.insertEntry(conn, txId, customerId, se);
                Ledger.insertEntry(conn, txId, Shard.BROKER_EUR, se.negate());
                Ledger.insertEntry(conn, txId, brokerAsset, su.negate());
                Ledger.insertEntry(conn, txId, assetAcct, su);
                Ledger.updateCachedBalance(conn, customerId, se);
                Ledger.updateCachedBalance(conn, Shard.BROKER_EUR, se.negate());
                Ledger.updateCachedBalance(conn, brokerAsset, su.negate());
                Ledger.updateCachedBalance(conn, assetAcct, su);

                Outbox.append(conn, "payments", txId.toString(),
                        "{\"type\":\"trade.executed\",\"txId\":\"" + txId +
                        "\",\"asset\":\"" + asset + "\",\"side\":\"" + (buy ? "buy" : "sell") +
                        "\",\"eur\":\"" + eur.toPlainString() + "\",\"units\":\"" + units.toPlainString() +
                        "\",\"price\":\"" + price.toPlainString() + "\",\"customer\":" + customerId + "}");
                conn.commit();
                return new Ledger.Ok();
            } catch (Exception e) {
                conn.rollback();
                if (e instanceof SQLException se2 && "23514".equals(se2.getSQLState()))
                    return new Ledger.InsufficientFunds();
                throw e;
            }
        }
    }

    // ------------------------------------------------------------------
    // settlement: the same four entries, but the numbers come from a VENUE
    // ------------------------------------------------------------------

    /**
     * Settle a fill the broker service reported.
     *
     * Structurally this is trade() with one difference that matters: the
     * units and the cash are GIVEN, not derived. A venue fills a quantity at
     * a price and those are facts; recomputing them here from cash / price
     * would introduce a rounding step and the two services would slowly
     * disagree about what the customer owns.
     *
     * Gated by the fill id, which is the broker's own primary key reused as
     * this bank's txId. Kafka will deliver the fill twice eventually and the
     * transactions table already knows how to answer that.
     */
    public static Ledger.TransferResult settleFill(UUID fillId, long customerId, String asset,
                                                   boolean buy, BigDecimal units, BigDecimal cash)
            throws SQLException {
        if (units.signum() <= 0 || cash.signum() <= 0)
            throw new IllegalArgumentException("units and cash must be positive");
        long assetAcct = customerId + ("btc".equals(asset) ? BTC : AAPL);
        long brokerAsset = "btc".equals(asset) ? Shard.BROKER_BTC : Shard.BROKER_AAPL;

        Shard home = Shards.forCustomer(customerId);
        try (Connection conn = home.open()) {
            conn.setAutoCommit(false);
            try {
                if (!Ledger.claimTx(conn, fillId, "settle:" + asset + ":" + (buy ? "buy" : "sell"))) {
                    conn.rollback();
                    return new Ledger.AlreadyProcessed();
                }
                long[] ids = {Shard.BROKER_EUR, brokerAsset, customerId, assetAcct};
                java.util.Arrays.sort(ids);              // the global lock order, unchanged
                Ledger.Account main = null, assetA = null;
                for (long lid : ids) {
                    Ledger.Account a = Ledger.lockAccount(conn, lid);
                    if (lid == customerId) main = a;
                    if (lid == assetAcct) assetA = a;
                }
                // the only decision that can fail, and it is local
                if (buy && main.balance().compareTo(cash) < 0) { conn.rollback(); return new Ledger.InsufficientFunds(); }
                if (!buy && assetA.balance().compareTo(units) < 0) { conn.rollback(); return new Ledger.InsufficientFunds(); }

                BigDecimal se = buy ? cash.negate() : cash;      // customer's EUR leg
                BigDecimal su = buy ? units : units.negate();    // customer's asset leg
                Ledger.insertEntry(conn, fillId, customerId, se);
                Ledger.insertEntry(conn, fillId, Shard.BROKER_EUR, se.negate());
                Ledger.insertEntry(conn, fillId, brokerAsset, su.negate());
                Ledger.insertEntry(conn, fillId, assetAcct, su);
                Ledger.updateCachedBalance(conn, customerId, se);
                Ledger.updateCachedBalance(conn, Shard.BROKER_EUR, se.negate());
                Ledger.updateCachedBalance(conn, brokerAsset, su.negate());
                Ledger.updateCachedBalance(conn, assetAcct, su);

                // the answer rides out on the SAME commit as the money · a
                // crash here cannot leave the broker waiting forever for a
                // settlement that silently happened
                Outbox.append(conn, Settlement.TOPIC_SETTLEMENTS, "settled:" + fillId,
                        "{\"type\":\"trade.settled\",\"fillId\":\"" + fillId +
                        "\",\"customer\":" + customerId +
                        ",\"symbol\":\"" + asset.toUpperCase() +
                        "\",\"units\":\"" + units.toPlainString() +
                        "\",\"cash\":\"" + cash.toPlainString() + "\"}");
                conn.commit();
                // A broker fill settling is money moving, and it arrives from
                // the broker's thread rather than from anyone's click, which is
                // exactly the class of event the dashboard used to be blind to.
                Metrics.inc("minibank_ledger_events_total", "kind=\"fill_settled\"");
                return new Ledger.Ok();
            } catch (Exception e) {
                conn.rollback();
                if (e instanceof SQLException se && "23514".equals(se.getSQLState()))
                    return new Ledger.InsufficientFunds();
                throw e;
            }
        }
    }

    /**
     * Record that settlement was REFUSED, and tell the broker.
     *
     * No money moves, so there is nothing to write entries for · but the
     * refusal still has to be a durable, idempotent fact, or a redelivered
     * fill would produce a second rejection event and the broker would
     * compensate twice. The gate is a deterministic id derived from the
     * fill, the same trick the saga's refund already uses.
     */
    public static void recordSettlementRefusal(UUID fillId, long customerId, String symbol,
                                               boolean buy, BigDecimal units, String reason)
            throws SQLException {
        UUID refusalId = UUID.nameUUIDFromBytes(
                ("refuse:" + fillId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Shard home = Shards.forCustomer(customerId);
        try (Connection conn = home.open()) {
            conn.setAutoCommit(false);
            try {
                if (!Ledger.claimTx(conn, refusalId, "settle-refused")) {
                    conn.rollback();
                    return;                                   // already told them
                }
                Outbox.append(conn, Settlement.TOPIC_SETTLEMENTS, "rejected:" + fillId,
                        "{\"type\":\"trade.rejected\",\"fillId\":\"" + fillId +
                        "\",\"customer\":" + customerId +
                        ",\"symbol\":\"" + symbol +
                        "\",\"side\":\"" + (buy ? "buy" : "sell") +
                        "\",\"units\":\"" + units.toPlainString() +
                        "\",\"reason\":\"" + Json.esc(reason) + "\"}");
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    // ------------------------------------------------------------------
    // the mortgage: borrowing changes nothing · and the books prove it
    // ------------------------------------------------------------------
    public static Ledger.TransferResult mortgage(UUID txId, long customerId, BigDecimal amount) throws SQLException {
        if (amount.signum() <= 0) throw new IllegalArgumentException("amount must be positive");
        if (amount.compareTo(MORTGAGE_CAP) > 0) throw new IllegalArgumentException("mortgage cap is " + MORTGAGE_CAP);
        long loanAcct = customerId + LOAN;
        Shard home = Shards.forCustomer(customerId);
        try (Connection conn = home.open()) {
            conn.setAutoCommit(false);
            try {
                if (!Ledger.claimTx(conn, txId, "mortgage")) {
                    conn.rollback();
                    return new Ledger.AlreadyProcessed();
                }
                Ledger.lockAccount(conn, customerId);
                Ledger.lockAccount(conn, loanAcct);
                Ledger.insertEntry(conn, txId, loanAcct, amount.negate());
                Ledger.insertEntry(conn, txId, customerId, amount);
                Ledger.updateCachedBalance(conn, loanAcct, amount.negate());
                Ledger.updateCachedBalance(conn, customerId, amount);
                Outbox.append(conn, "payments", txId.toString(),
                        "{\"type\":\"mortgage.approved\",\"txId\":\"" + txId +
                        "\",\"customer\":" + customerId + ",\"amount\":\"" + amount.toPlainString() + "\"}");
                conn.commit();
                Metrics.inc("minibank_ledger_events_total", "kind=\"loan_disbursed\"");
                return new Ledger.Ok();
            } catch (Exception e) {
                conn.rollback();
                if (e instanceof SQLException se && "23514".equals(se.getSQLState()))
                    return new Ledger.InsufficientFunds();
                throw e;
            }
        }
    }

    /** one product balance, read from the customer's home shard */
    public static BigDecimal balance(long customerId, long offset) throws SQLException {
        return Shards.forCustomer(customerId).balance(customerId + offset);
    }
}
