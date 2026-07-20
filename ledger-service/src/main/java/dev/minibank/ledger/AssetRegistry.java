package dev.minibank.ledger;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * THE ASSET REGISTRY · an asset account id is a LOOKUP, not a ternary.
 *
 * WHAT WAS WRONG. The ledger used to answer "which account holds this
 * customer's position?" with one line:
 *
 *     assetAcct = customerId + ("btc".equals(asset) ? BTC : AAPL)
 *
 * The else-branch is the whole problem. Every symbol that is not bitcoin
 * settled into the customer's APPLE account, and the books still summed to
 * zero in every currency on every shard · a wrong holding that passes every
 * check the bank runs. The only thing preventing a real mis-credit was that
 * the broker's catalog was seeded with exactly two rows, which is a
 * discipline, not a mechanism.
 *
 * WHAT REPLACES IT. Two tables per shard (db/shard/V4__asset_registry.sql):
 * asset_slots is one row per listed instrument, asset_accounts is one row per
 * (instrument, customer). Both are read through this class, and this class
 * FAILS CLOSED: an unregistered symbol raises UnknownAsset. There is no
 * default branch to fall into, which is the actual fix · generalising the
 * ternary while leaving a fallback would only have moved the bug.
 *
 * THE ID SCHEME, and why no coordination is needed. An allocated id is
 *
 *     ASSET_BASE (1e9) + slot * SLOT_STRIDE (1e6) + suffix
 *
 * with suffix 1 = the slot's broker account, 3 = its clearing account, and
 * >= 10 = a customer id. Those suffixes are not arbitrary: they are the same
 * numbers the bank already uses for BROKER_EUR and IN_TRANSIT at the top of
 * the id space, so a slot's clearing account always sorts BELOW its holding
 * accounts and the ascending-lock rule keeps holding by construction.
 *
 * The SLOT is derived from a stable hash of the symbol, not from a sequence.
 * That matters because the registry is per-shard: two shards must reach the
 * same id for the same instrument without talking to each other, and a
 * MAX(slot)+1 allocator gives that guarantee only as long as every shard sees
 * every registration in the same order · which is a coordination problem this
 * bank has deliberately avoided everywhere else. A hash needs no ordering at
 * all. The price is that two symbols could hash to the same slot; the answer
 * is that register() then FAILS, loudly, at the moment a human lists the
 * instrument, rather than quietly at the moment someone's money settles.
 * Listing is a rare, deliberate, business decision · a good place to put a
 * failure, and the exact opposite of where the old ternary put one.
 *
 * BTC and AAPL are seeded with their legacy ids and carry a legacy_offset, so
 * their holding accounts stay customerId + 200 / + 300 forever. Nothing
 * already in a database moves.
 */
public final class AssetRegistry {

    /** Everything this registry allocates lives at or above a billion · nine
     *  orders of magnitude above the largest id the legacy scheme can produce
     *  (customer 99 + HOLDS = 699). See the migration for the full map. */
    public static final long ASSET_BASE = 1_000_000_000L;
    public static final long SLOT_STRIDE = 1_000_000L;

    /** Inside a slot, the same reserved suffixes the bank uses at the top of
     *  the id space: the broker's leg, and the clearing account. */
    public static final long SUFFIX_BROKER = 1;
    public static final long SUFFIX_CLEARING = 3;

    /** Slots 0 and 1 belong to BTC and AAPL and are never derived, so a newly
     *  listed instrument cannot be handed bitcoin's slot. */
    public static final long FIRST_DERIVED_SLOT = 2;
    public static final long SLOT_LIMIT = 1_000_000L;

    /** the smallest offset the product shelf uses · a legacy asset offset
     *  below this is not an offset, it is a misread column */
    private static final long SAVINGS_OFFSET = 100;

    /** A symbol nobody listed. Thrown, never defaulted · this is the type
     *  that makes the settlement path fail closed. */
    public static final class UnknownAsset extends RuntimeException {
        public UnknownAsset(String symbol) {
            super("no asset account mapping for '" + symbol
                    + "' · list it with AssetRegistry.register before anything can settle into it");
        }
    }

    /**
     * One listed instrument, as the LEDGER sees it.
     *
     * multiplier is how many units of the underlying ONE unit of this
     * instrument controls: 1 for a share or a coin, 100 for an option
     * contract. It is here, and not only in the broker's catalog, because
     * HttpApi values a customer's holdings inside the ledger on a shard
     * connection and cannot read the broker's database. See
     * db/shard/V6__option_instruments.sql for why that duplication is the
     * least-bad of the three available options.
     *
     * expiresOn is NULL for anything that does not expire, which is every
     * instrument that is not an option. It is not a display field: it is what
     * lets an expired contract stop reading as a live position, and what lets
     * a 404 from the price feed be interpreted rather than guessed at.
     */
    public record Asset(String symbol, String currency, String label, long slot, Long legacyOffset,
                        long brokerAccount, long clearingAccount,
                        java.math.BigDecimal multiplier, String kind,
                        java.time.LocalDate expiresOn) {

        /**
         * HAS THIS CONTRACT EXPIRED as at the given date?
         *
         * Inclusive of the expiry date itself · an option trades ON its
         * expiry day and stops afterwards, so `isAfter` is the correct
         * comparison and `!isBefore` would retire it a day early.
         *
         * Something with no expiry never expires. That is a fact about the
         * instrument, not a missing value to be defaulted nervously.
         */
        public boolean expiredAsOf(java.time.LocalDate asOf) {
            return expiresOn != null && asOf.isAfter(expiresOn);
        }

        /** This customer's holding account for this instrument · the id the
         *  ternary used to guess. Legacy instruments keep their offset. */
        public long holdingFor(long customerId) {
            if (legacyOffset != null) {
                // a legacy offset must be one of the shelf offsets it was
                // seeded as · a zero here would return the customer's OWN
                // account, which is how a null column read wrong once already
                if (legacyOffset < SAVINGS_OFFSET)
                    throw new IllegalStateException("'" + symbol + "' has a nonsense legacy offset "
                            + legacyOffset + " · that resolves onto the customer's own account");
                return customerId + legacyOffset;
            }
            long id = ASSET_BASE + slot * SLOT_STRIDE + customerId;
            requireAllocatable(id, symbol);
            return id;
        }
    }

    private AssetRegistry() {}

    // ------------------------------------------------------------------
    // schema · mirrors db/shard/V4__asset_registry.sql so that a test which
    // builds a shard through createSchema gets the same tables Flyway builds
    // ------------------------------------------------------------------
    static void createTablesOn(Connection c) throws SQLException {
        try (var st = c.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS asset_slots (
                    symbol           TEXT   PRIMARY KEY,
                    currency         TEXT   NOT NULL UNIQUE,
                    label            TEXT   NOT NULL,
                    slot             BIGINT NOT NULL UNIQUE,
                    legacy_offset    BIGINT,
                    broker_account   BIGINT NOT NULL UNIQUE,
                    clearing_account BIGINT NOT NULL UNIQUE,
                    CONSTRAINT asset_slots_range CHECK (
                        legacy_offset IS NOT NULL
                        OR (broker_account >= 1000000000 AND clearing_account >= 1000000000)
                    )
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS asset_accounts (
                    symbol      TEXT   NOT NULL REFERENCES asset_slots(symbol),
                    customer_id BIGINT NOT NULL,
                    account_id  BIGINT NOT NULL UNIQUE,
                    PRIMARY KEY (symbol, customer_id)
                )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_asset_accounts_customer ON asset_accounts(customer_id)");
            // the two that predate the registry, with the ids they already have
            // a database migrated by an earlier build of this table has the
            // rows but not the column · add it before the seed needs it
            st.execute("ALTER TABLE asset_slots ADD COLUMN IF NOT EXISTS label TEXT NOT NULL DEFAULT ''");
            // mirrors db/shard/V6__option_instruments.sql · a test that builds
            // a shard through here must get the same columns Flyway builds, or
            // the multiplier is exercised by nothing that runs in CI
            st.execute("ALTER TABLE asset_slots ADD COLUMN IF NOT EXISTS multiplier NUMERIC(20,8) NOT NULL DEFAULT 1");
            st.execute("ALTER TABLE asset_slots ADD COLUMN IF NOT EXISTS kind TEXT NOT NULL DEFAULT 'equity'");
            st.execute("ALTER TABLE asset_slots ADD COLUMN IF NOT EXISTS expires_on DATE");
            st.execute("ALTER TABLE asset_slots DROP CONSTRAINT IF EXISTS asset_slots_multiplier_positive");
            st.execute("ALTER TABLE asset_slots ADD CONSTRAINT asset_slots_multiplier_positive CHECK (multiplier > 0)");
            st.execute("ALTER TABLE asset_slots DROP CONSTRAINT IF EXISTS asset_slots_expiry_only_dated");
            st.execute("ALTER TABLE asset_slots ADD CONSTRAINT asset_slots_expiry_only_dated"
                    + " CHECK (expires_on IS NULL OR kind = 'option')");
            st.execute("""
                INSERT INTO asset_slots(symbol, currency, label, slot, legacy_offset, broker_account, clearing_account,
                                        multiplier, kind, expires_on)
                VALUES ('BTC','BTC','bitcoin',0,200,5,8,1,'crypto',NULL),
                       ('AAPL','AAPL','apple stock',1,300,6,9,1,'equity',NULL)
                ON CONFLICT (symbol) DO NOTHING""");
        }
    }

    /** Every slot's own system accounts, on THIS shard · the broker's leg and
     *  the clearing account, exactly like the seeded system accounts. Called
     *  from Shard.createSchema, so a shard is never missing the other side of
     *  a trade it is asked to settle. */
    static void ensureSlotAccountsOn(Connection c, String kindExternal) throws SQLException {
        for (Asset a : all(c)) {
            ensureAccount(c, a.brokerAccount(), "broker", kindExternal, a.currency());
            ensureAccount(c, a.clearingAccount(), "in_transit", kindExternal, a.currency());
        }
    }

    // ------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------
    private static final String COLS =
            "SELECT symbol, currency, label, slot, legacy_offset, broker_account, clearing_account,"
                    + " multiplier, kind, expires_on FROM asset_slots";

    /** THE lookup. Throws rather than guessing · there is no else-branch. */
    public static Asset bySymbol(Connection c, String symbol) throws SQLException {
        String s = normalize(symbol);
        try (PreparedStatement ps = c.prepareStatement(COLS + " WHERE symbol = ?")) {
            ps.setString(1, s);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new UnknownAsset(symbol);
                return read(rs);
            }
        }
    }

    /** The same lookup keyed by the ledger CURRENCY · this is what the
     *  relocation path needs, because an account row knows its currency and
     *  not the symbol it was bought under. Null when the currency is not an
     *  asset (EUR, most obviously). */
    public static Asset byCurrency(Connection c, String currency) throws SQLException {
        if (currency == null) return null;
        try (PreparedStatement ps = c.prepareStatement(COLS + " WHERE currency = ?")) {
            ps.setString(1, currency);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        }
    }

    public static List<Asset> all(Connection c) throws SQLException {
        List<Asset> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(COLS + " ORDER BY slot");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(read(rs));
        }
        return out;
    }

    /** Is this symbol listed in the ledger? The HTTP trade path asks before
     *  it accepts an order, so the validation the Kafka path enforces and the
     *  validation the click path enforces are the same fact. */
    /**
     * Is this symbol listed on the shard that will actually SETTLE it?
     *
     * This used to ask shard 0 unconditionally while the trade settled on the
     * customer's home shard, so the gate and the settlement could disagree ·
     * and register() writes to each shard in its own transaction, so they
     * genuinely can. A symbol half-registered (shard 0 yes, shard 1 no) then
     * passed the gate for a uk customer and blew up at settlement, which is
     * the worst place to discover it: the venue has already filled.
     */
    public static boolean isRegistered(String symbol, long customerId) throws SQLException {
        try (Connection c = Shards.forCustomer(customerId).open()) {
            bySymbol(c, symbol);
            return true;
        } catch (UnknownAsset e) {
            return false;
        }
    }

    /** Listed on EVERY shard · what "listed" should mean before anyone trades it. */
    public static boolean isRegisteredEverywhere(String symbol) throws SQLException {
        for (Shard shard : Shards.all()) {
            try (Connection c = shard.open()) {
                bySymbol(c, symbol);
            } catch (UnknownAsset e) {
                return false;
            }
        }
        return true;
    }

    /**
     * wasNull() ON ITS OWN LINE, and it has to be.
     *
     * This was written as one expression, with `rs.wasNull() ? null : legacy`
     * inline in the constructor call · and Java evaluates arguments left to
     * right, so wasNull() answered about the LAST column read, which was the
     * slot, not the legacy offset. A null legacy_offset came back as 0, and
     * `holdingFor` returned customerId + 0 · the customer's own EUR account.
     * Every MSFT share would have settled into igor's current account, and
     * the per-currency audit would have caught it only because the currencies
     * differ. Exactly the shape of bug this class was written to delete,
     * reintroduced one layer down. The test caught it; the comment keeps it
     * caught.
     */
    private static Asset read(ResultSet rs) throws SQLException {
        String symbol = rs.getString(1);
        String currency = rs.getString(2);
        String label = rs.getString(3);
        long slot = rs.getLong(4);
        long legacy = rs.getLong(5);
        Long legacyOffset = rs.wasNull() ? null : legacy;
        long broker = rs.getLong(6);
        long clearing = rs.getLong(7);
        // multiplier is NOT NULL with a DEFAULT of 1, so this cannot come back
        // null from a migrated database · but a null here would mean "unknown
        // contract size", and valuing on an unknown contract size is the 100x
        // error this column exists to prevent. Refuse rather than assume 1.
        java.math.BigDecimal multiplier = rs.getBigDecimal(8);
        if (multiplier == null || multiplier.signum() <= 0)
            throw new IllegalStateException("'" + symbol + "' has multiplier " + multiplier
                    + " · a holding cannot be valued without a positive contract size");
        String kind = rs.getString(9);
        // getDate answers null for SQL NULL directly, so no wasNull() dance is
        // needed here · unlike legacy_offset above, whose getLong returns 0
        java.sql.Date exp = rs.getDate(10);
        return new Asset(symbol, currency, label, slot, legacyOffset, broker, clearing,
                multiplier, kind, exp == null ? null : exp.toLocalDate());
    }

    // ------------------------------------------------------------------
    // listing an instrument
    // ------------------------------------------------------------------
    /**
     * LIST an instrument in the ledger: allocate its slot, and create the
     * broker leg and the clearing account on EVERY shard.
     *
     * Idempotent · re-listing an instrument that is already listed is a
     * no-op, so this is safe on every boot. Not idempotent about
     * CONTRADICTIONS: asking to list a symbol under a different currency, or
     * a symbol whose slot is already taken, raises instead of quietly
     * rewriting a mapping that customers already hold balances under.
     *
     * This is deliberately a separate act from seeding the broker's catalog.
     * A tradable instrument needs BOTH: a row the broker can route, and an
     * account the ledger can settle into. The old comment in Catalog.java was
     * right that the third instrument is "a ledger change, not a row in this
     * table" · this method is that change, made once, for all instruments.
     */
    public static void register(String symbol, String currency) throws SQLException {
        register(symbol, currency, normalize(symbol).toLowerCase(Locale.ROOT));
    }

    public static void register(String symbol, String currency, String label) throws SQLException {
        // an instrument listed without saying otherwise is an ordinary spot
        // one: one unit is one unit, and it does not expire
        register(symbol, currency, label, java.math.BigDecimal.ONE, "equity", null);
    }

    /**
     * LIST a contract · the full form, with the contract size and the expiry.
     *
     * multiplier is how many units of the underlying one unit of this
     * instrument controls. A share's is 1. Note that this is NOT a special
     * case for options bolted onto a stock-shaped model: every instrument has
     * a multiplier, a stock's simply happens to be one, so there is no branch
     * anywhere that asks "is this an option?" before deciding how to value it.
     *
     * expiresOn is null for anything that does not expire.
     */
    public static void register(String symbol, String currency, String label,
                                java.math.BigDecimal multiplier, String kind,
                                java.time.LocalDate expiresOn) throws SQLException {
        String s = normalize(symbol);
        String ccy = normalize(currency);
        if (multiplier == null || multiplier.signum() <= 0)
            throw new IllegalArgumentException("'" + s + "' needs a positive multiplier, not " + multiplier
                    + " · a zero contract size values every holding of it at nothing");
        if (expiresOn != null && !"option".equals(kind))
            throw new IllegalArgumentException("'" + s + "' is kind '" + kind + "' and cannot expire"
                    + " · an expiry on a spot instrument would retire a holding that nothing should retire");
        long slot = derivedSlot(s);
        long broker = ASSET_BASE + slot * SLOT_STRIDE + SUFFIX_BROKER;
        long clearing = ASSET_BASE + slot * SLOT_STRIDE + SUFFIX_CLEARING;
        requireAllocatable(broker, s);
        requireAllocatable(clearing, s);

        for (Shard shard : Shards.all()) {
            try (Connection c = shard.open()) {
                Asset existing = null;
                try {
                    existing = bySymbol(c, s);
                } catch (UnknownAsset ignored) {
                    // not listed here yet · that is the whole point
                }
                if (existing != null) {
                    if (!existing.currency().equals(ccy))
                        throw new IllegalStateException("'" + s + "' is already listed against currency "
                                + existing.currency() + ", not " + ccy
                                + " · customers may already hold balances under the old mapping");
                    // The SAME argument as the currency check above, and it is
                    // the more dangerous of the two. Re-listing an instrument
                    // under a different contract size silently restates every
                    // existing holding of it by the ratio · the balances do not
                    // move, so the books still sum to zero in every currency,
                    // and only the valuation is wrong. That is precisely the
                    // shape of bug V4 exists to delete: wrong, and passing
                    // every check the bank runs.
                    if (existing.multiplier().compareTo(multiplier) != 0)
                        throw new IllegalStateException("'" + s + "' is already listed with multiplier "
                                + existing.multiplier().toPlainString() + ", not " + multiplier.toPlainString()
                                + " · customers may already hold positions valued under the old contract size");
                    ensureSlotAccounts(c, existing);
                    continue;
                }
                String clash = symbolAtSlot(c, slot);
                if (clash != null)
                    throw new IllegalStateException("'" + s + "' hashes to slot " + slot
                            + ", which already belongs to '" + clash
                            + "' · pick a different ticker or widen SLOT_LIMIT."
                            + " Failing here, at listing time, is the point: the alternative"
                            + " is two instruments sharing one holding account.");
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO asset_slots(symbol, currency, label, slot, legacy_offset,
                                                broker_account, clearing_account,
                                                multiplier, kind, expires_on)
                        VALUES (?,?,?,?,NULL,?,?,?,?,?) ON CONFLICT (symbol) DO NOTHING""")) {
                    ps.setString(1, s);
                    ps.setString(2, ccy);
                    ps.setString(3, label);
                    ps.setLong(4, slot);
                    ps.setLong(5, broker);
                    ps.setLong(6, clearing);
                    ps.setBigDecimal(7, multiplier);
                    ps.setString(8, kind);
                    ps.setDate(9, expiresOn == null ? null : java.sql.Date.valueOf(expiresOn));
                    ps.executeUpdate();
                }
                ensureSlotAccounts(c, bySymbol(c, s));
            }
        }
    }

    private static void ensureSlotAccounts(Connection c, Asset a) throws SQLException {
        ensureAccount(c, a.brokerAccount(), "broker", Ledger.KIND_EXTERNAL, a.currency());
        ensureAccount(c, a.clearingAccount(), "in_transit", Ledger.KIND_EXTERNAL, a.currency());
    }

    private static String symbolAtSlot(Connection c, long slot) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT symbol FROM asset_slots WHERE slot = ?")) {
            ps.setLong(1, slot);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    // ------------------------------------------------------------------
    // the customer's holding account · created LAZILY, on first trade
    // ------------------------------------------------------------------
    /**
     * WHICH ACCOUNT · recorded first, derived second.
     *
     * The derivation has to exist: two shards must reach the same id for the
     * same (instrument, customer) without coordinating, and only arithmetic
     * gives that. But once a customer HOLDS something, the id stops being a
     * derivation and becomes a fact, so the recorded row wins. That ordering
     * is the promise that a future change to ASSET_BASE, SLOT_STRIDE or the
     * hash cannot move money that already exists · it would allocate new
     * holdings elsewhere and leave every existing one exactly where it is.
     *
     * A row that disagrees with the derivation is therefore not corruption to
     * be overwritten; it is the older, more authoritative answer.
     */
    static long holdingIdFor(Connection c, Asset a, long customerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT account_id FROM asset_accounts WHERE symbol = ? AND customer_id = ?")) {
            ps.setString(1, a.symbol());
            ps.setLong(2, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return a.holdingFor(customerId);
    }

    /**
     * The account this customer's position in this instrument lives in,
     * creating it if this is the first time they have ever touched it.
     *
     * LAZY ON PURPOSE. The eager alternative is customers x instruments empty
     * accounts, which is a table that grows by multiplication for the benefit
     * of nobody · a customer who has never bought MSFT does not have an MSFT
     * holding, and a ledger that says otherwise is inventing rows to make a
     * screen easier to write.
     *
     * Runs on the CALLER'S connection, inside the caller's transaction: a
     * trade that rolls back leaves no account behind, and one that commits
     * commits the account with the money that justified it.
     */
    public static long ensureHolding(Connection c, String symbol, long customerId) throws SQLException {
        Asset a = bySymbol(c, symbol);
        long id = holdingIdFor(c, a, customerId);
        // the ACCOUNT first, unconditionally: the registry row can outlive a
        // demo reset that truncated accounts, and a mapping pointing at an
        // account that does not exist is worse than no mapping at all
        ensureAccount(c, id, a.label(), Ledger.KIND_CUSTOMER, a.currency());
        recordHolding(c, a.symbol(), customerId, id);
        return id;
    }

    /** Write down which account holds this · first write wins, because the
     *  recorded id is the authoritative one and re-recording must never move
     *  a holding that already exists. */
    static void recordHolding(Connection c, String symbol, long customerId, long accountId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO asset_accounts(symbol, customer_id, account_id) VALUES (?,?,?) "
                        + "ON CONFLICT (symbol, customer_id) DO NOTHING")) {
            ps.setString(1, normalize(symbol));
            ps.setLong(2, customerId);
            ps.setLong(3, accountId);
            ps.executeUpdate();
        }
    }

    /**
     * Create this customer's holding account on an EXPLICIT shard, without
     * touching money · what relocation needs so a shelf has somewhere to land.
     * Registers the routing row too, for the same reason Products.ensureOn
     * does: a customer in uk whose MSFT holding routes to eu is the same bug
     * this bank already fixed once for savings.
     */
    public static void ensureHoldingOn(Shard shard, String symbol, long customerId) throws SQLException {
        try (Connection c = shard.open()) {
            long id = ensureHolding(c, symbol, customerId);
            try {
                Directory.register(id, bySymbol(c, symbol).label(), shard.index);
            } catch (Exception ignored) {
                // no directory in arithmetic-router contexts (lesson tests)
            }
        }
    }

    private static void ensureAccount(Connection c, long id, String owner, String kind, String currency)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                // the floor travels with the kind (minicredit): a broker or
                // clearing slot is EXTERNAL and must stay unconstrained
                // (clearing accounts swing negative on every arrival), while a
                // customer holding keeps the fail-closed floor of 0
                "INSERT INTO accounts(id, owner, balance, version, kind, currency, min_balance) VALUES (?,?,0,0,?,?,?) "
                        + "ON CONFLICT (id) DO NOTHING")) {
            ps.setLong(1, id);
            ps.setString(2, owner);
            ps.setString(3, kind);
            ps.setString(4, currency);
            ps.setBigDecimal(5, Ledger.floorFor(kind));
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------
    // slot derivation · stable, coordination-free, and checked
    // ------------------------------------------------------------------
    /**
     * FNV-1a over the symbol's bytes, folded into the derivable slot range.
     *
     * Written out rather than borrowed from String.hashCode because this
     * number decides where money lands: it has to mean the same thing on
     * every shard, in every JVM, in five years' time, and a hash spelled out
     * in eight lines is a hash nobody can change by accident.
     */
    static long derivedSlot(String symbol) {
        long h = 0xcbf29ce484222325L;
        for (byte b : normalize(symbol).getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xffL);
            h *= 0x100000001b3L;
        }
        return Math.floorMod(h, SLOT_LIMIT - FIRST_DERIVED_SLOT) + FIRST_DERIVED_SLOT;
    }

    /**
     * The non-collision check, in code and not in hope.
     *
     * Everything the registry allocates must land at or above ASSET_BASE. An
     * id below it would be indistinguishable from a system account, a
     * customer, or somebody's savings · the class of bug this whole change
     * exists to remove. Cheap enough to run on every derivation, so it does.
     */
    /**
     * Refuse an id that could belong to something else.
     *
     * The first version of this only asserted id >= ASSET_BASE, which the
     * formula ASSET_BASE + slot*STRIDE + suffix satisfies unconditionally ·
     * a guard that cannot fail is decoration. The reachable collisions are
     * BETWEEN allocated ids, not with the legacy space: a suffix large
     * enough to reach into the next slot's range. So bound the suffix.
     */
    static void requireAllocatable(long id, String symbol) {
        if (id < ASSET_BASE)
            throw new IllegalStateException("asset account " + id + " for '" + symbol
                    + "' would land in the legacy id space (system 1-9, customers 10-99, shelf 110-699)");
        long withinSlot = (id - ASSET_BASE) % SLOT_STRIDE;
        if (withinSlot >= SLOT_STRIDE - 1)
            throw new IllegalStateException("asset account " + id + " for '" + symbol
                    + "' overflows its slot and would collide with the next instrument");
    }

    /**
     * A human label for a symbol, or the symbol itself if it is not listed.
     *
     * For SCREENS only. It never throws: a statement line for an instrument
     * that has since been delisted should still render, showing the ticker,
     * rather than taking the whole statement down. Money paths use bySymbol
     * and get UnknownAsset, which is the opposite trade-off and the right one
     * there.
     */
    public static String labelOrSymbol(Connection c, String symbol) {
        try {
            return bySymbol(c, symbol).label();
        } catch (Exception e) {
            return symbol == null ? "?" : symbol.toUpperCase(Locale.ROOT);
        }
    }

    static String normalize(String symbol) {
        if (symbol == null || symbol.isBlank()) throw new UnknownAsset(String.valueOf(symbol));
        return symbol.trim().toUpperCase(Locale.ROOT);
    }
}
