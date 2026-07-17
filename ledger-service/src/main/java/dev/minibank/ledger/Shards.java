package dev.minibank.ledger;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * STAGE 5 · THE ROUTER. One question, answered in one place:
 * "which machine does this customer live on?"
 *
 * DECISION: shard by CUSTOMER id. A customer's accounts, entries and
 * history all live on one shard, so everything about one customer is
 * local and ACID. (Revolut regions are this idea with geography in the
 * key: route by residency and the shard also satisfies data-residency
 * law · UK data on UK machines.)
 *
 * The demo routes id % 2 so you can see it with your eyes: igor (10) is
 * even -> shard0, coco (11) is odd -> shard1 · every igor->coco payment
 * exercises the cross-shard saga, live. A real fleet uses
 * hash(customer_id) mod N or a lookup service, same idea, more shards.
 *
 * System accounts (ids below 10: world, in_transit) exist on EVERY shard
 * · so a top-up never crosses shards either. Only customer<->customer
 * payments can, and the saga handles those.
 */
public final class Shards {

    /** ids below this are per-shard system accounts, not customers. */
    public static final long FIRST_CUSTOMER_ID = 10;

    /** STAGE 6: the routing rule became pluggable. Default: arithmetic
     *  (id mod N · load sharding). Production: the Directory (residency ·
     *  a lookup, because law is a fact about the customer, not a formula). */
    @FunctionalInterface
    public interface Resolver {
        int shardIndexOf(long customerId);
    }

    private static volatile Shard[] shards;
    private static volatile Resolver resolver;      // null = id mod N
    private static volatile String[] regionNames;   // null = "shard0"/"shard1"

    private Shards() {}

    public static void setResolver(Resolver r) {
        resolver = r;
    }

    public static void nameRegions(String... names) {
        regionNames = names;
    }

    public static String regionName(int index) {
        String[] n = regionNames;
        return n != null && index < n.length ? n[index] : "shard" + index;
    }

    public static void boot(String url0, String url1, String user, String password, int poolSize) throws SQLException {
        shards = new Shard[]{
                new Shard(0, url0, user, password, poolSize),
                new Shard(1, url1, user, password, poolSize),
        };
    }

    public static List<Shard> all() {
        return List.of(shards);
    }

    public static Shard s(int index) {
        return shards[index];
    }

    public static boolean isSystem(long accountId) {
        return accountId < FIRST_CUSTOMER_ID;
    }

    /** The routing function. With no resolver set: deterministic arithmetic.
     *  With the Directory plugged in: residency lookup (may throw
     *  Directory.CustomerMoving during a relocation · retry, briefly). */
    public static Shard forCustomer(long customerId) {
        Resolver r = resolver;
        int idx = r != null ? r.shardIndexOf(customerId) : (int) (customerId % shards.length);
        return shards[idx];
    }

    /** Where does this transfer run? System accounts live everywhere, so
     *  they take the shard of whichever CUSTOMER is involved. */
    public record Plan(Shard source, Shard dest, boolean crossShard) {}

    public static Plan plan(long fromId, long toId) {
        if (isSystem(fromId) && isSystem(toId))
            throw new IllegalArgumentException("system-to-system transfers are not a thing");
        if (isSystem(fromId)) { Shard h = forCustomer(toId);   return new Plan(h, h, false); }
        if (isSystem(toId))   { Shard h = forCustomer(fromId); return new Plan(h, h, false); }
        Shard s = forCustomer(fromId), d = forCustomer(toId);
        return new Plan(s, d, s != d);
    }

    // ------------------------------------------------------------------
    // fleet-wide facts
    // ------------------------------------------------------------------

    /** THE cross-shard invariant: sum of every shard's IN_TRANSIT balance
     *  = money currently in the pipe. Zero when all sagas have settled.
     *  Each shard balances alone at every instant; this is the one number
     *  that needs the whole fleet to compute. */
    public static BigDecimal inFlight() throws SQLException {
        BigDecimal sum = BigDecimal.ZERO;
        for (Shard s : all()) sum = sum.add(s.inTransitBalance());
        return sum;
    }

    // ------------------------------------------------------------------
    // bootstrap
    // ------------------------------------------------------------------
    public static void createAndSeed() throws SQLException {
        for (Shard s : all()) s.createSchema();
        seedCustomer(10, "igor", "500.00");
        seedCustomer(11, "coco", "500.00");
        // oscar shares igor's region: igor->oscar shows the LOCAL path,
        // igor->coco the cross-region saga · both stories, one demo cast.
        seedCustomer(12, "oscar", "1000.00");
    }

    private static void seedCustomer(long id, String owner, String amount) throws SQLException {
        Shard home = forCustomer(id);
        if (home.hasAccount(id)) return;
        home.createCustomer(id, owner);
        // funded from the LOCAL world account · same shard, plain ACID
        home.transferLocal(UUID.randomUUID(), Shard.WORLD, id, new BigDecimal(amount));
        System.out.println("seeded: " + owner + " on " + home.name + " with " + amount);
    }
}
