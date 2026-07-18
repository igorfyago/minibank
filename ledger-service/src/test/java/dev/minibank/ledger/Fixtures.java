package dev.minibank.ledger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * SHARED TEST FIXTURES · and the two ways this suite used to lose a race it
 * was never running in.
 *
 * These lessons talk to real databases, and those databases are not
 * exclusively ours: a dev instance of the bank left running against the same
 * Compose stack polls the outbox every 500ms, reads accounts for the app, and
 * holds ordinary AccessShareLocks the whole time. Two consequences, both of
 * which failed builds for reasons that had nothing to do with the code:
 *
 *   TRUNCATE needs an AccessExclusiveLock on every table it names, so ONE
 *   concurrent reader is enough to deadlock the reset ("deadlock detected:
 *   process A waits for AccessExclusiveLock ... blocked by process B").
 *   Fixed by naming the tables in one consistent order everywhere, bounding
 *   the wait, and RETRYING · a busy database should delay a reset, not fail
 *   a suite.
 *
 *   The outbox is a QUEUE someone else may drain. A test that reads only
 *   UNPUBLISHED rows is asserting "the relay has not shipped this yet",
 *   which is a race, not a property. Read the row whatever its
 *   published_at: the lesson is that the event was written inside the money
 *   commit, not that nobody has forwarded it.
 *
 * None of this weakens what the lessons prove. It removes an assumption the
 * lessons never meant to make: that they own the machine.
 */
final class Fixtures {

    /** Same order everywhere, so two resets can never deadlock each other. */
    private static final String WIPE_LEDGER = "TRUNCATE entries, transactions, outbox, accounts CASCADE";
    private static final int ATTEMPTS = 6;

    private Fixtures() {}

    /** Empty a shard and rebuild its schema and system accounts. */
    static void resetShard(Shard s) throws SQLException {
        retrying(() -> {
            try (Connection c = s.open(); Statement st = c.createStatement()) {
                st.execute("SET lock_timeout = '4s'");
                st.execute(WIPE_LEDGER);
            }
        });
        s.createSchema();
    }

    /** Every shard in the fleet. */
    static void resetShards() throws SQLException {
        for (Shard s : Shards.all()) resetShard(s);
    }

    /** The stage 1 to 4 lessons predate sharding and use one database
     *  through Db.open() · same contention, same retry. */
    static void onSingleDb(String... statements) throws SQLException {
        retrying(() -> {
            try (Connection c = Db.open(); Statement st = c.createStatement()) {
                st.execute("SET lock_timeout = '4s'");
                for (String sql : statements) st.execute(sql);
            }
        });
    }

    /** Empty the routing table and the in-process cache that fronts it. */
    static void resetDirectory() throws SQLException {
        retrying(() -> {
            try (Connection c = DriverManager.getConnection(directoryUrl(), "minibank", "minibank");
                 Statement st = c.createStatement()) {
                st.execute("SET lock_timeout = '4s'");
                st.execute("TRUNCATE customers");
            }
        });
        Directory.clearCache();
    }

    private static String directoryUrl() {
        return System.getenv().getOrDefault("MINIBANK_DB_URL", "jdbc:postgresql://localhost:5433/minibank")
                .replaceFirst("/minibank$", "/minibank_directory");
    }

    /**
     * Every outbox row on this shard, published or not.
     *
     * Outbox.pollUnpublishedOn is the RELAY's read and is right for the
     * relay. For a test it is a race: if anything else is shipping events,
     * the row is gone from that view within milliseconds of being written.
     */
    static List<Outbox.Event> allOutboxOn(Connection c) throws SQLException {
        List<Outbox.Event> events = new ArrayList<>();
        try (var ps = c.prepareStatement("SELECT id, topic, key, payload FROM outbox ORDER BY id");
             var rs = ps.executeQuery()) {
            while (rs.next())
                events.add(new Outbox.Event(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
        }
        return events;
    }

    /** The one event whose key starts with this prefix · fails loudly if absent. */
    static Outbox.Event outboxEvent(Shard s, String keyPrefix) throws SQLException {
        try (Connection c = s.open()) {
            return allOutboxOn(c).stream()
                    .filter(e -> e.key().startsWith(keyPrefix))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "no outbox event with key prefix '" + keyPrefix + "' on " + s.name));
        }
    }

    // ------------------------------------------------------------------
    /** Retry a reset through lock contention · deadlock (40P01) and lock
     *  timeout (55P03) both mean "someone else is using this", not "broken". */
    private static void retrying(SqlAction action) throws SQLException {
        SQLException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                action.run();
                return;
            } catch (SQLException e) {
                String state = e.getSQLState();
                if (!"40P01".equals(state) && !"55P03".equals(state)) throw e;
                last = e;
                try {
                    Thread.sleep(120L * attempt);          // linear backoff
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw new SQLException("could not reset the database after " + ATTEMPTS
                + " attempts · something else is holding locks on it", last);
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }
}
