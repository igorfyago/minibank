package dev.minibank.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * MINICREDIT · THE CREDIT LIFECYCLE, WITHOUT A SINGLE NEW STATE TABLE.
 *
 * A real credit card is the card the shelf already has PLUS TIME: statement
 * cycles, interest on what you carry, a minimum payment, a limit that can
 * move. The usual build grows a cycles table, an accrual job and a scheduler
 * · three new places for the truth to drift apart. This build grows none of
 * them, because every one of those facts is DERIVABLE from the append-only
 * entries table:
 *
 *   a CYCLE is a UTC calendar month · a name for a time window, not a row
 *   a BALANCE AT an instant is SUM(amount) over entries before it · stable
 *     forever, because entries are append-only (the V3 trigger) and
 *     created_at is immutable
 *   POSTED DEBT is balanceAt(card) + balanceAt(holds) · adding the holds
 *     account cancels in-flight authorizations, so an uncaptured hold is
 *     never counted as debt and a release never looks like a repayment
 *
 * "Closing" a cycle is therefore not a scheduled event but an idempotent
 * POSTING operation: it may run at any time at or after the due date, is
 * invoked lazily by any statement read of a past-due cycle, and both charges
 * it can post (interest, late fee) ride deterministic transaction ids
 * through the same claimTx gate every transfer uses · a recompute or a
 * concurrent close loses the primary-key claim and posts nothing. A missed
 * run costs nothing; a double run is impossible by construction.
 *
 * KNOWN LIMIT, stated rather than hidden: relocation moves BALANCES, not
 * entry history (Relocation walks departBalance/arriveBalance). A relocated
 * customer's whole balance arrives as one entry stamped at move time, so
 * their statement history on the new shard begins at the relocation · the
 * old months remain readable on the old shard's archive, but this class,
 * asking the home shard, sees a single opening line. The fix would be an
 * entry-preserving relocation walk, which is Relocation's story, not this
 * file's.
 */
public final class Credit {

    /** the bank's income account · the other side of every interest and
     *  late-fee charge. id 2 is the last free reserved id below 10. */
    public static final long BANK_INCOME = 2;
    /** 2 percent per month on the carried balance · a deliberate
     *  simplification (real cards compound daily APR; one flat monthly rate
     *  keeps the lesson about the LEDGER, not about day-count conventions) */
    public static final BigDecimal MONTHLY_RATE = new BigDecimal("0.02");
    public static final BigDecimal MIN_PAY_RATE = new BigDecimal("0.05");
    public static final BigDecimal MIN_PAY_FLOOR = new BigDecimal("10.00");
    public static final BigDecimal LATE_FEE = new BigDecimal("10.00");
    public static final int GRACE_DAYS = 21;

    private Credit() {}

    // ------------------------------------------------------------------
    // the cycle · a name for a month of UTC time
    // ------------------------------------------------------------------
    public record CycleId(int year, int month) {
        public CycleId {
            if (month < 1 || month > 12) throw new IllegalArgumentException("month must be 1..12: " + month);
        }
        public String key() { return String.format("%04d-%02d", year, month); }
        public Instant start() {
            return OffsetDateTime.of(year, month, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant();
        }
        public Instant end() {
            int y = month == 12 ? year + 1 : year, m = month == 12 ? 1 : month + 1;
            return OffsetDateTime.of(y, m, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant();
        }
        public Instant due() { return end().plus(Duration.ofDays(GRACE_DAYS)); }
        public CycleId previous() {
            return month == 1 ? new CycleId(year - 1, 12) : new CycleId(year, month - 1);
        }
        public static CycleId of(Instant t) {
            OffsetDateTime d = t.atOffset(ZoneOffset.UTC);
            return new CycleId(d.getYear(), d.getMonthValue());
        }
        public static CycleId parse(String key) {
            var m = java.util.regex.Pattern.compile("(\\d{4})-(\\d{2})").matcher(key == null ? "" : key);
            if (!m.matches()) throw new IllegalArgumentException("cycle key is YYYY-MM: " + key);
            return new CycleId(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        }
    }

    /** one charge the close may post · state: posted | none | pending | blocked */
    public record Posting(BigDecimal amount, String state, UUID txId) {}

    public record Line(UUID txId, Instant at, BigDecimal amount, String kind) {}

    public record Statement(CycleId cycle, BigDecimal opening, BigDecimal closing,
                            BigDecimal statementDebt, BigDecimal minimumDue, Instant dueAt,
                            BigDecimal payments, Posting interest, Posting lateFee, List<Line> lines) {}

    public record CloseResult(CycleId cycle, Posting interest, Posting lateFee) {}

    // ------------------------------------------------------------------
    // the primitive · a balance BEFORE an instant, out of the ledger
    // ------------------------------------------------------------------
    /** SUM of entries before t · stable forever, because entries are
     *  append-only and created_at never changes after insert */
    public static BigDecimal balanceAt(Connection c, long accountId, Instant t) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(SUM(amount), 0) FROM entries WHERE account_id = ? AND created_at < ?")) {
            ps.setLong(1, accountId);
            ps.setTimestamp(2, Timestamp.from(t));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    /** card + holds · the number that matters. An authorization moved money
     *  card -> holds, so summing both makes an uncaptured hold vanish from
     *  the debt figure and a release never read as a repayment. Negative
     *  means the customer owes. */
    public static BigDecimal postedDebtAt(Connection c, long customerId, Instant t) throws SQLException {
        return balanceAt(c, customerId + Products.CARD, t)
                .add(balanceAt(c, customerId + Products.HOLDS, t));
    }

    /** what the customer owed at t, as a non-negative figure */
    private static BigDecimal debtAt(Connection c, long customerId, Instant t) throws SQLException {
        return postedDebtAt(c, customerId, t).negate().max(BigDecimal.ZERO);
    }

    /**
     * Repayments landing in a window, measured FROM THE LEDGER · positive
     * entries on the card account whose transaction has no leg on the holds
     * account. Not inferred from balance deltas, which would count a hold
     * release as a repayment and hide payments behind new spending. A café
     * refund does count · it is inbound money with no hold leg, and the
     * statement copy says so rather than letting anyone discover it.
     */
    private static BigDecimal paymentsBetween(Connection c, long customerId,
                                              Instant after, Instant upTo) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(SUM(e.amount), 0) FROM entries e " +
                "WHERE e.account_id = ? AND e.amount > 0 AND e.created_at > ? AND e.created_at <= ? " +
                "AND NOT EXISTS (SELECT 1 FROM entries h WHERE h.tx_id = e.tx_id AND h.account_id = ?)")) {
            ps.setLong(1, customerId + Products.CARD);
            ps.setTimestamp(2, Timestamp.from(after));
            ps.setTimestamp(3, Timestamp.from(upTo));
            ps.setLong(4, customerId + Products.HOLDS);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    // ------------------------------------------------------------------
    // cycle arithmetic · every figure a pure function of the entries
    // ------------------------------------------------------------------
    static BigDecimal statementDebt(Connection c, long customerId, CycleId cycle) throws SQLException {
        return debtAt(c, customerId, cycle.end());
    }

    /** min of two caps IS the grace rule: capping at debtAtDue means what
     *  was repaid inside the 21 days is never charged (full repayment =>
     *  zero); capping at statementDebt means new spending during grace can
     *  never inflate the base. */
    static BigDecimal carried(Connection c, long customerId, CycleId cycle) throws SQLException {
        return statementDebt(c, customerId, cycle).min(debtAt(c, customerId, cycle.due()));
    }

    static BigDecimal minimumDue(BigDecimal statementDebt) {
        if (statementDebt.signum() == 0) return BigDecimal.ZERO;
        BigDecimal rated = MIN_PAY_RATE.multiply(statementDebt).setScale(2, RoundingMode.HALF_UP);
        return statementDebt.min(MIN_PAY_FLOOR.max(rated));
    }

    static UUID interestTxId(long customerId, CycleId cycle) {
        return UUID.nameUUIDFromBytes(
                ("interest:" + (customerId + Products.CARD) + ":" + cycle.key()).getBytes(StandardCharsets.UTF_8));
    }

    static UUID lateFeeTxId(long customerId, CycleId cycle) {
        return UUID.nameUUIDFromBytes(
                ("latefee:" + (customerId + Products.CARD) + ":" + cycle.key()).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // the close · an idempotent posting operation, not a scheduled event
    // ------------------------------------------------------------------
    /**
     * Post what cycle C is owed: interest on the carried balance, then the
     * late fee if the minimum went unpaid. TWO INDEPENDENT COMMITS, each
     * behind its own deterministic id · a crash between them is safe because
     * each is separately idempotent, and any later run finishes the job.
     *
     * At the floor the CHECK constraint vetoes a charge (23514): reported as
     * "blocked", and the next close attempt retries the SAME id, which posts
     * exactly once when a repayment or a limit raise makes headroom.
     * Deferred, never lost, never doubled.
     */
    public static CloseResult closeCycle(long customerId, CycleId cycle) throws SQLException {
        if (Instant.now().isBefore(cycle.due()))
            throw new IllegalArgumentException("cycle " + cycle.key() + " is not due until " + cycle.due());
        Shard home = Shards.forCustomer(customerId);
        Posting interest, lateFee;
        try (Connection c = home.open()) {
            BigDecimal carried = carried(c, customerId, cycle);
            BigDecimal interestAmt = MONTHLY_RATE.multiply(carried).setScale(2, RoundingMode.HALF_UP);
            interest = interestAmt.signum() == 0
                    ? new Posting(BigDecimal.ZERO, "none", null)
                    : charge(c, customerId, interestTxId(customerId, cycle), "interest", interestAmt);

            BigDecimal stmtDebt = statementDebt(c, customerId, cycle);
            BigDecimal minimum = minimumDue(stmtDebt);
            BigDecimal paid = paymentsBetween(c, customerId, cycle.end(), cycle.due());
            boolean missed = stmtDebt.signum() > 0 && paid.compareTo(minimum) < 0;
            lateFee = !missed
                    ? new Posting(BigDecimal.ZERO, "none", null)
                    : charge(c, customerId, lateFeeTxId(customerId, cycle), "late-fee", LATE_FEE);
        }
        return new CloseResult(cycle, interest, lateFee);
    }

    /** one charge: card debited, bank income credited, gated by claimTx ·
     *  the same double-entry discipline as every transfer in this bank */
    private static Posting charge(Connection conn, long customerId, UUID txId,
                                  String kind, BigDecimal amount) throws SQLException {
        long card = customerId + Products.CARD;
        conn.setAutoCommit(false);
        try {
            if (!Ledger.claimTx(conn, txId, kind)) {
                conn.rollback();
                // the claim already exists: this exact charge posted before ·
                // amounts are deterministic recomputations, so the figure is
                // the same one that landed
                return new Posting(amount, "posted", txId);
            }
            // ordered locking · BANK_INCOME (2) sorts below every card id
            Ledger.lockAccount(conn, BANK_INCOME);
            Ledger.lockAccount(conn, card);
            Ledger.insertEntry(conn, txId, card, amount.negate());
            Ledger.insertEntry(conn, txId, BANK_INCOME, amount);
            Ledger.updateCachedBalance(conn, card, amount.negate());
            Ledger.updateCachedBalance(conn, BANK_INCOME, amount);
            conn.commit();
            return new Posting(amount, "posted", txId);
        } catch (Exception e) {
            conn.rollback();
            // the account sits at its floor: the CHECK vetoed the charge.
            // Deferred revenue AND deferred debt · the claim rolled back with
            // us, so the next close retries the same id.
            if (e instanceof SQLException se && "23514".equals(se.getSQLState()))
                return new Posting(amount, "blocked", txId);
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    // ------------------------------------------------------------------
    // the statement · the cycle told as an API answer
    // ------------------------------------------------------------------
    /** Lazily closes a past-due cycle first · reading a statement is what
     *  makes closing happen, so no scheduler is load-bearing. */
    public static Statement statement(long customerId, CycleId cycle) throws SQLException {
        Instant now = Instant.now();
        Posting interest, lateFee;
        if (!now.isBefore(cycle.due())) {
            CloseResult r = closeCycle(customerId, cycle);
            interest = r.interest();
            lateFee = r.lateFee();
        } else {
            interest = null;   // filled below, as a pending projection
            lateFee = null;
        }
        Shard home = Shards.forCustomer(customerId);
        try (Connection c = home.open()) {
            BigDecimal opening = postedDebtAt(c, customerId, cycle.start());
            BigDecimal closing = postedDebtAt(c, customerId, cycle.end());
            BigDecimal stmtDebt = closing.negate().max(BigDecimal.ZERO);
            BigDecimal minimum = minimumDue(stmtDebt);
            BigDecimal paid = paymentsBetween(c, customerId, cycle.end(),
                    now.isBefore(cycle.due()) ? now : cycle.due());
            if (interest == null) {
                // inside grace: the charge has not happened and may never ·
                // what it WOULD be if the clock stopped now, said as pending
                BigDecimal wouldCarry = stmtDebt.min(debtAt(c, customerId, now));
                BigDecimal projected = MONTHLY_RATE.multiply(wouldCarry).setScale(2, RoundingMode.HALF_UP);
                interest = new Posting(projected, projected.signum() == 0 ? "none" : "pending",
                        interestTxId(customerId, cycle));
                boolean satisfied = stmtDebt.signum() == 0 || paid.compareTo(minimum) >= 0;
                // payments only grow, so "satisfied" is already final; a
                // shortfall is merely pending until the due date passes
                lateFee = satisfied ? new Posting(BigDecimal.ZERO, "none", null)
                        : new Posting(LATE_FEE, "pending", lateFeeTxId(customerId, cycle));
            }
            List<Line> lines = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT e.tx_id, e.created_at, e.amount, t.kind FROM entries e " +
                    "JOIN transactions t ON t.id = e.tx_id " +
                    "WHERE e.account_id = ? AND e.created_at >= ? AND e.created_at < ? ORDER BY e.created_at, e.id")) {
                ps.setLong(1, customerId + Products.CARD);
                ps.setTimestamp(2, Timestamp.from(cycle.start()));
                ps.setTimestamp(3, Timestamp.from(cycle.end()));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next())
                        lines.add(new Line(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(),
                                rs.getBigDecimal(3), rs.getString(4)));
                }
            }
            return new Statement(cycle, opening, closing, stmtDebt, minimum, cycle.due(),
                    paid, interest, lateFee, lines);
        }
    }

    /** what next month's interest would be if the clock stopped now ·
     *  NEVER persisted, and the API labels it an estimate in the payload */
    public static BigDecimal interestEstimate(long customerId) throws SQLException {
        Shard home = Shards.forCustomer(customerId);
        try (Connection c = home.open()) {
            return MONTHLY_RATE.multiply(debtAt(c, customerId, Instant.now()))
                    .setScale(2, RoundingMode.HALF_UP);
        }
    }

    /** money leaving the card+holds pair this window, out of the ledger:
     *  purchases, captures and fees, with releases cancelled and repayments
     *  netted back out · payments - (debt change) rearranged */
    public static BigDecimal spentBetween(long customerId, Instant from, Instant to) throws SQLException {
        Shard home = Shards.forCustomer(customerId);
        try (Connection c = home.open()) {
            BigDecimal net = postedDebtAt(c, customerId, to).subtract(postedDebtAt(c, customerId, from));
            return paymentsBetween(c, customerId, from, to).subtract(net).max(BigDecimal.ZERO);
        }
    }

    // ------------------------------------------------------------------
    // the limit · a per-row floor, changed by one UPDATE the CHECK validates
    // ------------------------------------------------------------------
    public sealed interface LimitResult permits LimitOk, DebtAboveLimit, NotACard {}
    public record LimitOk() implements LimitResult {}
    /** lowering a limit below current utilization: the row CHECK refuses the
     *  UPDATE itself · a business answer from the schema, like every veto */
    public record DebtAboveLimit() implements LimitResult {}
    public record NotACard() implements LimitResult {}

    /** the card's limit, read from the row the CHECK enforces · -min_balance */
    public static BigDecimal limit(long customerId) throws SQLException {
        Shard home = Shards.forCustomer(customerId);
        try (Connection c = home.open(); PreparedStatement ps = c.prepareStatement(
                "SELECT min_balance FROM accounts WHERE id = ? AND kind = 'credit'")) {
            ps.setLong(1, customerId + Products.CARD);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("no card for customer " + customerId);
                BigDecimal floor = rs.getBigDecimal(1);
                return floor == null ? BigDecimal.ZERO : floor.negate();
            }
        }
    }

    /**
     * One transaction: move the floor, record the fact. Postgres re-validates
     * the row CHECK on UPDATE, so lowering a limit under the customer's
     * current debt fails with 23514 and maps to a refusal · NOTHING in the
     * authorize path changes, and the acquirer still cannot learn the limit.
     */
    public static LimitResult changeLimit(long customerId, BigDecimal newLimit) throws SQLException {
        if (newLimit == null || newLimit.signum() < 0)
            throw new IllegalArgumentException("a credit limit is zero or more");
        long card = customerId + Products.CARD;
        Shard home = Shards.forCustomer(customerId);
        try (Connection conn = home.open()) {
            conn.setAutoCommit(false);
            try {
                BigDecimal oldFloor;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT min_balance FROM accounts WHERE id = ? AND kind = 'credit' FOR UPDATE")) {
                    ps.setLong(1, card);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) { conn.rollback(); return new NotACard(); }
                        oldFloor = rs.getBigDecimal(1);
                    }
                }
                BigDecimal newFloor = newLimit.negate();
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE accounts SET min_balance = ? WHERE id = ? AND kind = 'credit'")) {
                    ps.setBigDecimal(1, newFloor);
                    ps.setLong(2, card);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO credit_limit_events(account_id, old_floor, new_floor) VALUES (?,?,?)")) {
                    ps.setLong(1, card);
                    ps.setBigDecimal(2, oldFloor == null ? BigDecimal.ZERO : oldFloor);
                    ps.setBigDecimal(3, newFloor);
                    ps.executeUpdate();
                }
                conn.commit();
                return new LimitOk();
            } catch (Exception e) {
                conn.rollback();
                if (e instanceof SQLException se && "23514".equals(se.getSQLState()))
                    return new DebtAboveLimit();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }
}
