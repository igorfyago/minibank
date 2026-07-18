package dev.minibank.ledger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * STAGE 6 · THE DIRECTORY: routing becomes a FACT, not a FORMULA.
 *
 * Stage 5 routed by arithmetic (id mod 2) · fine for spreading load, but
 * regions do not exist for load. They exist for LAW: a UK customer's data
 * must live on UK machines, an EEA customer's in the EEA. Residency is an
 * attribute of the customer, and no hash function can compute an attribute
 * · so the router asks a lookup service: this directory.
 *
 * It is its own service with its OWN tiny database (the third one ·
 * database-per-service is a habit by now). The hot path is served from an
 * in-process cache: the home region of a customer changes approximately
 * never, which makes it the world's most cacheable data. (A real fleet
 * puts a TTL + change events on this cache; one process needs neither.)
 *
 * The directory also owns the one moving part of stage 6: the MOVING flag.
 * While a customer relocates between regions, the router refuses to start
 * new transfers for them · a write-pause measured in milliseconds, and the
 * honest price of moving state between machines.
 */
public final class Directory {

    /** Thrown by the router while a customer is mid-relocation: not an
     *  error, an instruction · retry in a moment. */
    public static final class CustomerMoving extends RuntimeException {
        public CustomerMoving(long id) { super("customer " + id + " is relocating between regions · retry"); }
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
        // Flyway owns the directory schema · db/directory/V*.sql
        String url = base.substring(0, base.lastIndexOf('/') + 1) + DB;
        Migrate.run(url, "minibank", "minibank", "classpath:db/directory");
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

    /**
     * The SSO subject → customer id lookup · null when the subject is not
     * linked to anyone.
     *
     * NULL, NOT AN EXCEPTION, and that is the one place this method
     * deliberately parts company with owner() and load() above. Those throw
     * IllegalArgumentException("unknown customer") because being asked to
     * route an id that does not exist is a bug in the caller. Being handed a
     * subject nobody has linked is the ordinary case during a permissive
     * rollout · every one of the 25 demo customers is in exactly that state ·
     * and it must be indistinguishable from every other reason the caller
     * ends up anonymous. See SsoIdentity.customerFor: a service that answers
     * differently for "no token" and "a token for someone I don't know" is an
     * oracle for enumerating which humans hold accounts here.
     *
     * Cached, in its own map. The routing cache above is
     * Map&lt;customerId, Home&gt; and cannot serve a reverse lookup, so this
     * needs a second one · see the comment in the body for why it is not
     * optional, and why linkSso is its only invalidator.
     */
    public static Long customerForSso(String sub) throws SQLException {
        if (sub == null || sub.isBlank()) return null;
        // CACHED, and it has to be. openOwnDb() is a bare DriverManager
        // connection · a fresh TCP connect and auth handshake, no pool. This
        // runs once per AUTHENTICATED request, so uncached it would open a
        // connection per request on the exact day enforcement is switched on
        // and traffic starts carrying tokens: the directory would run out of
        // connections and anonymous requests would start failing beside the
        // authenticated ones. Found by an adversarial review before it could
        // be discovered in production.
        //
        // Cacheable for the same reason routing is: a subject's customer id is
        // written once at link time and never changes. Misses are cached too,
        // because during a dark launch almost nobody is linked and a miss is
        // the common path · linkSso evicts, so a new link is seen at once.
        Optional<Long> hit = SSO_CACHE.get(sub);
        if (hit != null) return hit.orElse(null);
        try (Connection c = openOwnDb();
             PreparedStatement ps = c.prepareStatement("SELECT customer_id FROM sso_customers WHERE sso_sub = ?")) {
            ps.setString(1, sub);
            try (ResultSet rs = ps.executeQuery()) {
                Long found = rs.next() ? rs.getLong(1) : null;
                SSO_CACHE.put(sub, Optional.ofNullable(found));
                return found;
            }
        }
    }

    /** sso_sub -> customer id. Absence is cached as an empty Optional, so a
     *  ConcurrentHashMap (which cannot hold nulls) can still remember a miss. */
    private static final Map<String, Optional<Long>> SSO_CACHE = new ConcurrentHashMap<>();

    /**
     * Bind an SSO subject to a customer · idempotent, first link wins.
     *
     * ON CONFLICT DO NOTHING with no target on purpose: the table has TWO
     * unique constraints (the subject, and the customer) and this must be a
     * no-op for both. Naming (sso_sub) would swallow the replay of the same
     * link and still throw on "this customer is already linked to a different
     * subject" · which is the interesting collision, and the one a retrying
     * signup is most likely to hit.
     *
     * Same shape as register() one screen up, for the same reason: a restart,
     * a double-submitted signup form and a retried request must all land on
     * the state the first one wrote, rather than fighting over it.
     */
    public static void linkSso(String sub, long customerId) throws SQLException {
        SSO_CACHE.remove(sub);   // a new link must be visible immediately
        try (Connection c = openOwnDb();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO sso_customers(sso_sub, customer_id) VALUES (?,?) ON CONFLICT DO NOTHING")) {
            ps.setString(1, sub);
            ps.setLong(2, customerId);
            ps.executeUpdate();
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

    /** The pointer flip · the last step of a relocation: new home, not moving. */
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

    /** Every real customer, ids only (product accounts route through the
     *  same table but are not customers · they live at offsets >= 100). */
    public static java.util.List<Long> customerIds() throws SQLException {
        java.util.List<Long> ids = new java.util.ArrayList<>();
        try (Connection c = openOwnDb();
             var st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT customer_id FROM customers WHERE customer_id < 100 ORDER BY customer_id")) {
            while (rs.next()) ids.add(rs.getLong(1));
        }
        return ids;
    }

    /** read-only connection for the API layer (the X-ray shows the routing table). */
    public static Connection openForRead() throws SQLException {
        return openOwnDb();
    }

    /** tests only: a fresh cache between lessons */
    static void clearCache() {
        cache.clear();
    }
}
