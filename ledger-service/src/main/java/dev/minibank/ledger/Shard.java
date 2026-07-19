package dev.minibank.ledger;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * STAGE 5 · ONE SHARD: a complete little bank on its own machine.
 *
 * Sharding is the write-scaling move: when one Postgres cannot carry the
 * write load (replicas only scale READS), you split the CUSTOMERS across
 * independent databases. Each shard holds everything about its customers ·
 * accounts, ledger entries, its own outbox · so everyday operations stay
 * single-shard, single-transaction, fully ACID. Same six steps as stage 1;
 * only the address changed.
 *
 * THE PRICE, faced honestly: a transfer between customers on DIFFERENT
 * shards can no longer be one ACID transaction · there is no BEGIN that
 * spans two machines (two-phase commit exists and nobody wants it: it
 * blocks everyone while a coordinator makes up its mind, and the
 * coordinator is a new single point of failure). The industry answer is a
 * SAGA · two local transactions glued by the machinery we already built:
 *
 *   depart (source shard, ACID):  igor −30, IN_TRANSIT +30, outbox event
 *   ... Kafka carries the event, at-least-once ...
 *   arrive (dest shard, ACID):    IN_TRANSIT −30, coco +30, gated by txId
 *
 * IN_TRANSIT is a clearing account · the double-entry way to say "the money
 * is in the pipe". Each shard's books balance at every instant, and the sum
 * of all IN_TRANSIT balances across the fleet = money currently in flight
 * (zero when the pipes are drained). Real banks settle across borders with
 * exactly this pattern (nostro/vostro accounts).
 *
 * WHY THIS NEEDS NO DISTRIBUTED LOCK: the only decision that can FAIL is
 * "does the payer have the money" · and the payer lives on the source
 * shard, where depart() checks it under a plain local row lock. Credits
 * cannot fail (a missing destination bounces: refund(), the saga's
 * compensating transaction). That is WHY you shard by customer and not,
 * say, by transaction id: it keeps every fallible decision local.
 */
public final class Shard {

    /** System accounts exist on EVERY shard (ids below 10 are reserved):
     *  the world (money enters the bank), the clearing account, the broker
     *  (the other side of every asset trade, one leg per currency) and the
     *  café (a merchant for card payments). */
    public static final long WORLD = 1;
    public static final long IN_TRANSIT = 3;
    public static final long BROKER_EUR = 4;
    public static final long BROKER_BTC = 5;
    public static final long BROKER_AAPL = 6;
    public static final long CAFE = 7;
    /** A clearing account PER CURRENCY. The sum-zero audit groups by
     *  (tx_id, currency), so a BTC balance cannot ride a EUR clearing
     *  account: the tx would sum to zero overall and to non-zero in each
     *  currency alone. Real banks hold one nostro per currency for the
     *  same reason. Used when a relocation moves a whole product shelf. */
    public static final long IN_TRANSIT_BTC = 8;
    public static final long IN_TRANSIT_AAPL = 9;

    /** The clearing account money of this currency travels through. */
    public static long inTransitFor(String currency) {
        return switch (currency) {
            case "BTC"  -> IN_TRANSIT_BTC;
            case "AAPL" -> IN_TRANSIT_AAPL;
            default     -> IN_TRANSIT;
        };
    }

    public final int index;
    public final String name;
    private final MiniPool pool;   // stage 4 pays off: a pool per shard

    public Shard(int index, String url, String user, String password, int poolSize) throws SQLException {
        this.index = index;
        this.name = "shard" + index;
        this.pool = new MiniPool(url, user, password, poolSize);
    }

    public Connection open() throws SQLException {
        return pool.borrow(5, TimeUnit.SECONDS);
    }

    public MiniPool pool() { return pool; }

    // ------------------------------------------------------------------
    // schema + system accounts
    // ------------------------------------------------------------------
    public void createSchema() throws SQLException {
        try (Connection c = open()) {
            Ledger.createSchemaOn(c);
            ensureSystemAccount(c, WORLD, "world", "EUR");
            ensureSystemAccount(c, IN_TRANSIT, "in_transit", "EUR");
            ensureSystemAccount(c, IN_TRANSIT_BTC, "in_transit", "BTC");
            ensureSystemAccount(c, IN_TRANSIT_AAPL, "in_transit", "AAPL");
            ensureSystemAccount(c, BROKER_EUR, "broker", "EUR");
            ensureSystemAccount(c, BROKER_BTC, "broker", "BTC");
            ensureSystemAccount(c, BROKER_AAPL, "broker", "AAPL");
            ensureSystemAccount(c, CAFE, "cafe", "EUR");
        }
    }

    private static void ensureSystemAccount(Connection c, long id, String owner, String currency) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO accounts(id, owner, balance, version, kind, currency) VALUES (?,?,0,0,?,?) ON CONFLICT (id) DO NOTHING")) {
            ps.setLong(1, id);
            ps.setString(2, owner);
            ps.setString(3, Ledger.KIND_EXTERNAL);
            ps.setString(4, currency);
            ps.executeUpdate();
        }
    }

    public void createCustomer(long id, String owner) throws SQLException {
        try (Connection c = open()) {
            Ledger.createAccountOn(c, id, owner, Ledger.KIND_CUSTOMER);
        }
    }

    // ------------------------------------------------------------------
    // the easy case: both accounts on THIS shard · stage 1, unchanged
    // ------------------------------------------------------------------
    public Ledger.TransferResult transferLocal(UUID txId, long fromId, long toId, BigDecimal amount) throws SQLException {
        try (Connection c = open()) {
            return Ledger.transferOn(c, txId, fromId, toId, amount);
        }
    }

    // ------------------------------------------------------------------
    // the saga, half one: money leaves the payer INTO the pipe
    // ------------------------------------------------------------------
    public Ledger.TransferResult depart(UUID txId, long fromId, long toId, BigDecimal amount) throws SQLException {
        if (amount.signum() <= 0) throw new IllegalArgumentException("amount must be positive");
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                // the SAME idempotency gate · a retried "send" departs once
                if (!Ledger.claimTx(conn, txId, "depart")) {
                    conn.rollback();
                    return new Ledger.AlreadyProcessed();
                }
                // ordered locking, local rows only: IN_TRANSIT (3) sorts
                // before every customer id, so the order is fixed here too
                Ledger.lockAccount(conn, IN_TRANSIT);
                Ledger.Account from = Ledger.lockAccount(conn, fromId);

                // THE decision that can fail · and it is LOCAL. This line is
                // why the bank shards by customer: the payer's money and the
                // payer's lock are always on the same machine.
                if (Ledger.KIND_CUSTOMER.equals(from.kind()) && from.balance().compareTo(amount) < 0) {
                    conn.rollback();
                    return new Ledger.InsufficientFunds();
                }

                Ledger.insertEntry(conn, txId, fromId, amount.negate());
                Ledger.insertEntry(conn, txId, IN_TRANSIT, amount);
                Ledger.updateCachedBalance(conn, fromId, amount.negate());
                Ledger.updateCachedBalance(conn, IN_TRANSIT, amount);

                // the event IS the second half of the transfer. It commits
                // with the money (outbox) and Kafka will deliver it
                // at-least-once · so arrival must be idempotent, and is.
                Outbox.append(conn, "payments", "departed:" + txId,
                        "{\"type\":\"transfer.departed\",\"txId\":\"" + txId +
                        "\",\"from\":" + fromId + ",\"to\":" + toId +
                        ",\"amount\":\"" + amount.toPlainString() + "\"}");

                conn.commit();
                return new Ledger.Ok();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    // ------------------------------------------------------------------
    // the saga, half two: money leaves the pipe INTO the recipient
    // ------------------------------------------------------------------
    public Ledger.TransferResult arrive(UUID txId, long toId, BigDecimal amount) throws SQLException {
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                // the SAME txId gates here too · but on THIS shard's own
                // transactions table. Kafka may deliver the event five
                // times; the money arrives once.
                if (!Ledger.claimTx(conn, txId, "arrive")) {
                    conn.rollback();
                    return new Ledger.AlreadyProcessed();
                }
                if (!accountExists(conn, toId)) {
                    // the money already left the source shard · this is not
                    // an exception, it is a fact the saga must compensate.
                    conn.rollback();
                    return new Ledger.NoSuchAccount();
                }
                Ledger.lockAccount(conn, IN_TRANSIT);
                Ledger.lockAccount(conn, toId);

                Ledger.insertEntry(conn, txId, IN_TRANSIT, amount.negate());
                Ledger.insertEntry(conn, txId, toId, amount);
                Ledger.updateCachedBalance(conn, IN_TRANSIT, amount.negate());
                Ledger.updateCachedBalance(conn, toId, amount);
                // no funds check: credits cannot fail. And no new outbox
                // event · the departed event already told the world.
                conn.commit();
                return new Ledger.Ok();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    // ------------------------------------------------------------------
    // the compensating transaction: the bounce
    // ------------------------------------------------------------------
    /** Destination didn't exist: put the money back where it came from.
     *  Gated by a DETERMINISTIC id derived from the original tx · so even
     *  the refund is idempotent if the bounce is processed twice. */
    public Ledger.TransferResult refund(UUID origTxId, long fromId, BigDecimal amount) throws SQLException {
        UUID refundId = UUID.nameUUIDFromBytes(("refund:" + origTxId).getBytes(StandardCharsets.UTF_8));
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                if (!Ledger.claimTx(conn, refundId, "refund", origTxId)) {
                    conn.rollback();
                    return new Ledger.AlreadyProcessed();
                }
                Ledger.lockAccount(conn, IN_TRANSIT);
                Ledger.lockAccount(conn, fromId);

                Ledger.insertEntry(conn, refundId, IN_TRANSIT, amount.negate());
                Ledger.insertEntry(conn, refundId, fromId, amount);
                Ledger.updateCachedBalance(conn, IN_TRANSIT, amount.negate());
                Ledger.updateCachedBalance(conn, fromId, amount);

                Outbox.append(conn, "payments", "bounced:" + origTxId,
                        "{\"type\":\"transfer.bounced\",\"txId\":\"" + origTxId +
                        "\",\"from\":" + fromId +
                        ",\"amount\":\"" + amount.toPlainString() + "\"}");
                conn.commit();
                // After the commit, never before: a refund that rolled back is
                // not a refund, and a counter that says otherwise is a lie that
                // outlives the exception in a graph nobody re-reads.
                Metrics.inc("minibank_ledger_events_total", "kind=\"saga_compensate\"");
                return new Ledger.Ok();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    // ------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------
    public BigDecimal balance(long accountId) throws SQLException {
        try (Connection c = open()) {
            return Ledger.cachedBalanceOn(c, accountId);
        }
    }

    /** This shard's slice of "money in the pipe": positive where transfers
     *  depart, negative where they arrive. The FLEET-WIDE sum is the real
     *  number · see Shards.inFlight(). */
    public BigDecimal inTransitBalance() throws SQLException {
        return balance(IN_TRANSIT);
    }

    public boolean hasAccount(long id) throws SQLException {
        try (Connection c = open()) {
            return accountExists(c, id);
        }
    }

    private static boolean accountExists(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM accounts WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // ------------------------------------------------------------------
    // the shelf mover · relocation moves EVERY account, not just the main one
    // ------------------------------------------------------------------
    /**
     * Take an account's ENTIRE balance out of this shard, into the clearing
     * account of its OWN currency. The saga's depart(), generalized on the
     * two axes a product shelf needs:
     *
     *   SIGN      savings travel as a credit, a card debt as a debit. Both
     *             are "move the balance to zero", so both are the same line
     *             of code · which is the honest double-entry answer to
     *             "how do you move a liability between machines".
     *   CURRENCY  the clearing leg matches the account's currency, so the
     *             per-currency sum-zero audit passes on BOTH shards.
     *
     * No funds check: this moves exactly what is there, so it cannot
     * overdraw, and the account lands on exactly zero. Returns the amount
     * moved (ZERO for an already-empty account · entries forbid amount = 0).
     */
    public BigDecimal departBalance(UUID txId, long accountId) throws SQLException {
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                if (!Ledger.claimTx(conn, txId, "relocate:depart")) {
                    // a replayed leg answers with what it moved the first
                    // time, so the caller's arrive() can still finish
                    conn.rollback();
                    return movedAmount(conn, txId, accountId).negate();
                }
                // currency first, WITHOUT a lock: it never changes, and we
                // need it to know which clearing account to lock. Then lock
                // ascending (clearing ids are < 10, accounts >= 10), the
                // same global ordering rule every other transaction obeys.
                long clearing = inTransitFor(currencyOf(conn, accountId));
                Ledger.lockAccount(conn, clearing);
                BigDecimal amount = Ledger.lockAccount(conn, accountId).balance();
                if (amount.signum() == 0) {          // nothing to move
                    conn.commit();
                    return BigDecimal.ZERO;
                }
                Ledger.insertEntry(conn, txId, accountId, amount.negate());
                Ledger.insertEntry(conn, txId, clearing, amount);
                Ledger.updateCachedBalance(conn, accountId, amount.negate());
                Ledger.updateCachedBalance(conn, clearing, amount);
                conn.commit();
                return amount;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    /** The other half: the same amount lands on this shard, out of the
     *  matching clearing account. Gated by the SAME txId on THIS shard's
     *  transactions table · replay lands the money once. Returns true only
     *  when THIS call is the one that moved it, so callers can report what
     *  actually happened instead of what was attempted. */
    public boolean arriveBalance(UUID txId, long accountId, BigDecimal amount) throws SQLException {
        if (amount.signum() == 0) return false;
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                if (!Ledger.claimTx(conn, txId, "relocate:arrive")) {
                    conn.rollback();
                    return false;
                }
                long clearing = inTransitFor(currencyOf(conn, accountId));
                Ledger.lockAccount(conn, clearing);
                Ledger.lockAccount(conn, accountId);
                Ledger.insertEntry(conn, txId, clearing, amount.negate());
                Ledger.insertEntry(conn, txId, accountId, amount);
                Ledger.updateCachedBalance(conn, clearing, amount.negate());
                Ledger.updateCachedBalance(conn, accountId, amount);
                conn.commit();
                return true;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    /** what a given tx already moved out of a given account (replay support) */
    private static BigDecimal movedAmount(Connection c, UUID txId, long accountId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT amount FROM entries WHERE tx_id = ? AND account_id = ?")) {
            ps.setObject(1, txId);
            ps.setLong(2, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO;
            }
        }
    }

    private static String currencyOf(Connection c, long accountId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT currency FROM accounts WHERE id = ?")) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("no such account: " + accountId);
                return rs.getString(1);
            }
        }
    }

    public void close() {
        pool.close();
    }
}
