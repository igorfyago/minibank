package dev.minibank.ledger;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * STAGE 1 — THE DOUBLE-ENTRY LEDGER (with a cached balance, derived honestly).
 *
 * Money never appears or vanishes; it only MOVES. Every transaction writes
 * entries that sum to exactly zero, inside one ACID database transaction.
 * The account row keeps a cached balance, updated in that same transaction;
 * the ledger remains the truth, the cache is a projection, and a
 * reconciliation query can always prove they agree.
 *
 * THE CONCURRENCY POINT TO INTERNALIZE (system-design interview gold):
 * correctness does NOT come from Java. `synchronized` or ReentrantLock only
 * protect ONE JVM — production runs many instances against one database, so
 * the database must own the locking. Java's job is throughput (virtual
 * threads, later), not correctness.
 *
 * Two mechanisms carry everything:
 *   1. ORDERED LOCKING: both account rows are locked with SELECT ... FOR
 *      UPDATE in ASCENDING ID ORDER, always. Two transfers touching the same
 *      two accounts can never hold one lock each and wait for the other —
 *      no deadlock, by construction. (The test suite first provokes a real
 *      deadlock with wrong-order locking so you can see Postgres kill it.)
 *   2. IDEMPOTENCY VIA PRIMARY KEY: the caller supplies the transaction id.
 *      A retry inserts the same id, hits the primary key, and is recognised
 *      as "already done" — money cannot move twice. Retries become safe.
 */
public final class Ledger {

    /** Customer accounts can never go below zero. External accounts (the
     *  outside world: merchants, top-up rails) may go negative — that is
     *  exactly how money enters and leaves the bank. */
    public static final String KIND_CUSTOMER = "customer";
    public static final String KIND_EXTERNAL = "external";

    public sealed interface TransferResult permits Ok, AlreadyProcessed, InsufficientFunds {}
    public record Ok() implements TransferResult {}
    public record AlreadyProcessed() implements TransferResult {}
    public record InsufficientFunds() implements TransferResult {}

    private Ledger() {}

    // ------------------------------------------------------------------
    // schema
    // ------------------------------------------------------------------
    public static void createTables() throws SQLException {
        try (Connection c = Db.open(); var st = c.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS accounts (
                    id      BIGINT PRIMARY KEY,
                    owner   TEXT   NOT NULL,
                    balance NUMERIC(12,2) NOT NULL,
                    version BIGINT NOT NULL DEFAULT 0
                )""");
            st.execute("ALTER TABLE accounts ADD COLUMN IF NOT EXISTS kind TEXT NOT NULL DEFAULT 'customer'");
            // The business rule lives IN the schema: customers can never go
            // negative; external accounts may (money enters the bank through
            // them). Stage 0's blanket "balance >= 0" must become kind-aware.
            st.execute("ALTER TABLE accounts DROP CONSTRAINT IF EXISTS accounts_balance_check");
            st.execute("ALTER TABLE accounts ADD CONSTRAINT accounts_balance_check " +
                       "CHECK (kind = 'external' OR balance >= 0)");
            st.execute("""
                CREATE TABLE IF NOT EXISTS transactions (
                    id         UUID PRIMARY KEY,
                    kind       TEXT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS entries (
                    id         BIGSERIAL PRIMARY KEY,
                    tx_id      UUID   NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
                    account_id BIGINT NOT NULL REFERENCES accounts(id)     ON DELETE CASCADE,
                    amount     NUMERIC(12,2) NOT NULL CHECK (amount <> 0),
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_entries_account ON entries(account_id, id)");
        }
        // the outbox is not optional decoration: a transfer writes to it,
        // so the ledger cannot exist without it.
        Outbox.createTable();
    }

    /** Accounts are born empty. Money only arrives BY TRANSFER — customer
     *  accounts are funded from an external "world" account. That keeps the
     *  invariant pure: cached balance == SUM(ledger entries), for everyone,
     *  from the very first cent. */
    public static void createAccount(long id, String owner, String kind) throws SQLException {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO accounts(id, owner, balance, version, kind) VALUES (?,?,0,0,?)")) {
            ps.setLong(1, id);
            ps.setString(2, owner);
            ps.setString(3, kind);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------
    // the transfer — the heart of the bank
    // ------------------------------------------------------------------
    public static TransferResult transfer(UUID txId, long fromId, long toId, BigDecimal amount) throws SQLException {
        if (amount.signum() <= 0) throw new IllegalArgumentException("amount must be positive");
        if (fromId == toId) throw new IllegalArgumentException("cannot transfer to self");

        try (Connection conn = Db.open()) {
            conn.setAutoCommit(false);                       // BEGIN
            try {
                // 1. IDEMPOTENCY GATE. Claim the transaction id first. If it
                //    already exists, this transfer already happened: do nothing.
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO transactions(id, kind) VALUES (?, 'transfer') ON CONFLICT (id) DO NOTHING")) {
                    ps.setObject(1, txId);
                    if (ps.executeUpdate() == 0) {
                        conn.rollback();
                        return new AlreadyProcessed();
                    }
                }

                // 2. ORDERED LOCKING. Lock BOTH account rows, always ascending
                //    by id, regardless of who pays whom. Deadlock is impossible
                //    because every transfer acquires locks in the same order.
                long first = Math.min(fromId, toId);
                long second = Math.max(fromId, toId);
                Account from = null, to = null;
                for (long id : new long[]{first, second}) {
                    Account a = lockAccount(conn, id);
                    if (id == fromId) from = a; else to = a;
                }

                // 3. THE BUSINESS RULE. Customers cannot go negative; the
                //    external world can (that is how money enters the bank).
                if (KIND_CUSTOMER.equals(from.kind) && from.balance.compareTo(amount) < 0) {
                    conn.rollback();                         // nothing happened, atomically
                    return new InsufficientFunds();
                }

                // 4. THE DOUBLE ENTRY. Two rows, summing to zero, one truth.
                insertEntry(conn, txId, fromId, amount.negate());
                insertEntry(conn, txId, toId, amount);

                // 5. THE CACHE, in the same ACID transaction as the truth.
                updateCachedBalance(conn, fromId, amount.negate());
                updateCachedBalance(conn, toId, amount);

                // 6. THE ECHO (stage 2). The event that tells the rest of the
                //    bank "this payment happened" is written INTO THIS SAME
                //    TRANSACTION. Money and event commit together or not at
                //    all — the transactional outbox. (Hand-rolled JSON is fine
                //    here; real fleets use schemas — a later stage.)
                Outbox.append(conn, "payments", txId.toString(),
                        "{\"type\":\"payment.completed\",\"txId\":\"" + txId +
                        "\",\"from\":" + fromId + ",\"to\":" + toId +
                        ",\"amount\":\"" + amount.toPlainString() + "\"}");

                conn.commit();                               // all six steps, or none
                return new Ok();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    private record Account(long id, String kind, BigDecimal balance) {}

    private static Account lockAccount(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT kind, balance FROM accounts WHERE id = ? FOR UPDATE")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("no such account: " + id);
                return new Account(id, rs.getString(1), rs.getBigDecimal(2));
            }
        }
    }

    private static void insertEntry(Connection conn, UUID txId, long accountId, BigDecimal amount) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO entries(tx_id, account_id, amount) VALUES (?,?,?)")) {
            ps.setObject(1, txId);
            ps.setLong(2, accountId);
            ps.setBigDecimal(3, amount);
            ps.executeUpdate();
        }
    }

    private static void updateCachedBalance(Connection conn, long accountId, BigDecimal delta) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE accounts SET balance = balance + ? WHERE id = ?")) {
            ps.setBigDecimal(1, delta);
            ps.setLong(2, accountId);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------
    // reads: the cache, the truth, and the checks that they agree
    // ------------------------------------------------------------------

    /** Fast read: one row. This is what the app shows. */
    public static BigDecimal cachedBalance(long accountId) throws SQLException {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement("SELECT balance FROM accounts WHERE id = ?")) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    /** Slow read: the truth, recomputed from every entry ever written. */
    public static BigDecimal ledgerBalance(long accountId) throws SQLException {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COALESCE(SUM(amount), 0) FROM entries WHERE account_id = ?")) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    /** Bank-wide invariant #1: every transaction's entries sum to zero.
     *  Must ALWAYS return an empty list. One query audits the whole bank. */
    public static List<UUID> sumZeroViolations() throws SQLException {
        List<UUID> bad = new ArrayList<>();
        try (Connection c = Db.open(); var st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT tx_id FROM entries GROUP BY tx_id HAVING SUM(amount) <> 0")) {
            while (rs.next()) bad.add(rs.getObject(1, UUID.class));
        }
        return bad;
    }

    /** Bank-wide invariant #2: every cached balance equals SUM(its entries).
     *  The reconciliation control — catches any code path that ever breaks the
     *  cache contract. Works because accounts are born empty and all money
     *  moves by transfer. This is a continuous data-quality check in one query. */
    public static List<Long> driftedAccounts() throws SQLException {
        List<Long> bad = new ArrayList<>();
        try (Connection c = Db.open(); var st = c.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT a.id
                     FROM accounts a
                     LEFT JOIN (SELECT account_id, SUM(amount) AS s
                                FROM entries GROUP BY account_id) e
                            ON e.account_id = a.id
                     WHERE a.balance <> COALESCE(e.s, 0)
                     """)) {
            while (rs.next()) bad.add(rs.getLong(1));
        }
        return bad;
    }
}
