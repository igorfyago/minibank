package dev.minibank.ledger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * STAGE 6 — THE DIRECTORY: routing becomes a FACT, not a FORMULA.
 *
 * Stage 5 routed by arithmetic (id mod 2) — fine for spreading load, but
 * regions do not exist for load. They exist for LAW: a UK customer's data
 * must live on UK machines, an EEA customer's in the EEA. Residency is an
 * attribute of the customer, and no hash function can compute an attribute
 * — so the router asks a lookup service: this directory.
 *
 * It is its own service with its OWN tiny database (the third one —
 * database-per-service is a habit by now). The hot path is served from an
 * in-process cache: the home region of a customer changes approximately
 * never, which makes it the world's most cacheable data. (A real fleet
 * puts a TTL + change events on this cache; one process needs neither.)
 *
 * The directory also owns the one moving part of stage 6: the MOVING flag.
 * While a customer relocates between regions, the router refuses to start
 * new transfers for them — a write-pause measured in milliseconds, and the
 * honest price of moving state between machines.
 */
public final class Directory {

    /** Thrown by the router while a customer is mid-relocation: not an
     *  error, an instruction — retry in a moment. */
    public static final class CustomerMoving extends RuntimeException {
        public CustomerMoving(long id) { super("customer " + id + " is relocating between regions — retry"); }
    }

    private static final String DB = "minibank_directory";

    private record Home(int shard, boolean moving) {}
    private static final Map<Long, Home> cache = new ConcurrentHashMap<>();

    private Directory() {}

    private static Connection openOwnDb() throws SQLException {
        String base = System.getenv().getOrDefault("MINIBANK_DB_URL", "jdbc:postgresql://localhost:5433/minibank");
        String url = base.substring(0, base.lastIndexOf('/') + 1) + DB;
        return DriverManager.getConnection(url, "minibank", "minibank");
    }

    public static void createOwnDatabase() throws SQLException {
        String base = System.getenv().getOrDefault("MINIBANK_DB_URL", "jdbc:postgresql://localhost:5433/minibank");
        try (Connection c = DriverManager.getConnection(base, "minibank", "minibank");
             var st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + DB + "'")) {
            if (!rs.next()) {
                try (var create = c.createStatement()) {
                    create.execute("CREATE DATABASE " + DB);
                }
            }
        }
        try (Connection c = openOwnDb(); var st = c.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS customers (
                    customer_id BIGINT PRIMARY KEY,
                    owner       TEXT NOT NULL,
                    shard       INT  NOT NULL,
                    moving      BOOLEAN NOT NULL DEFAULT false
                )""");
        }
    }

    /** First registration wins; re-registering an existing customer is a
     *  no-op (so restarts never un-relocate anyone). */
    public static void register(long customerId, String owner, int shard) throws SQLException {
        try (Connection c = openOwnDb();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO customers(customer_id, owner, shard) VALUES (?,?,?) ON CONFLICT (customer_id) DO NOTHING")) {
            ps.setLong(1, customerId);
            ps.setString(2, owner);
            ps.setInt(3, shard);
            ps.executeUpdate();
        }
        cache.remove(customerId);
    }

    /** THE routing lookup. Throws CustomerMoving during a relocation. */
    public static int shardOf(long customerId) {
        Home h = cache.computeIfAbsent(customerId, Directory::load);
        if (h.moving()) {
            cache.remove(customerId);   // don't cache a transient state
            throw new CustomerMoving(customerId);
        }
        return h.shard();
    }

    private static Home load(long customerId) {
        try (Connection c = openOwnDb();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT shard, moving FROM customers WHERE customer_id = ?")) {
            ps.setLong(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("unknown customer: " + customerId);
                return new Home(rs.getInt(1), rs.getBoolean(2));
            }
        } catch (SQLException e) {
            throw new RuntimeException("directory unavailable", e);
        }
    }

    public static String owner(long customerId) throws SQLException {
        try (Connection c = openOwnDb();
             PreparedStatement ps = c.prepareStatement("SELECT owner FROM customers WHERE customer_id = ?")) {
            ps.setLong(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("unknown customer: " + customerId);
                return rs.getString(1);
            }
        }
    }

    public static void setMoving(long customerId, boolean moving) throws SQLException {
        try (Connection c = openOwnDb();
             PreparedStatement ps = c.prepareStatement("UPDATE customers SET moving = ? WHERE customer_id = ?")) {
            ps.setBoolean(1, moving);
            ps.setLong(2, customerId);
            ps.executeUpdate();
        }
        cache.remove(customerId);
    }

    /** The pointer flip — the last step of a relocation: new home, not moving. */
    public static void flip(long customerId, int newShard) throws SQLException {
        try (Connection c = openOwnDb();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE customers SET shard = ?, moving = false WHERE customer_id = ?")) {
            ps.setInt(1, newShard);
            ps.setLong(2, customerId);
            ps.executeUpdate();
        }
        cache.remove(customerId);
    }

    /** tests only: a fresh cache between lessons */
    static void clearCache() {
        cache.clear();
    }
}
