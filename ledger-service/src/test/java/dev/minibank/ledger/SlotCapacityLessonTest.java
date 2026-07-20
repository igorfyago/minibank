package dev.minibank.ledger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SLOT CAPACITY · the id space widens, and nothing recorded moves.
 *
 * SLOT_LIMIT was one million, which reads as plenty and is nothing against an
 * option CHAIN: one underlying's chain (8 expiries x 100 strikes x calls and
 * puts = 1,600 OCC symbols) has an expected 1.28 colliding pairs at a million
 * slots · a ~72% chance that listing a single chain refuses at least one
 * perfectly good contract. register() failing loudly at listing time is the
 * designed behaviour; a gate that trips on most chains is a cap, not a
 * safety. At 10^12 the same chain's collision chance is ~1.3e-6.
 *
 *   lesson 1  THE BLOCKER, EXECUTABLE · a pinned 1,600-contract chain
 *             collides under the old constant and is collision-free under
 *             the new one. Revert SLOT_LIMIT to 1e6 and this lesson fails.
 *   lesson 2  THE MIGRATION, PROVEN THE FINGERPRINT WAY · reconstruct a
 *             representative database (legacy rows, a derived row, an
 *             old-formula row the current hash would not produce, recorded
 *             holdings), md5-fingerprint every recorded id, apply
 *             V10__slot_capacity.sql, fingerprint again: byte-identical, and
 *             a second application plus a re-registration boot is a no-op.
 *             If any code path re-derived a recorded id, the resolution
 *             assertions and the fingerprint would both fail.
 *   lesson 3  THE CHECK · a row whose accounts disagree with its slot's
 *             arithmetic is refused by the DATABASE, not by hoping the Java
 *             that wrote it was the current Java.
 *   lesson 4  the legacy pins hold against the new constant · bitcoin is
 *             still customerId+200, apple customerId+300, accounts 5/6/8/9.
 *
 * Requires: docker compose up -d   (shards :5434/:5435, directory on :5433)
 */
class SlotCapacityLessonTest {

    static final long IGOR = 10;
    static final int EU = 0;

    static final long OLD_LIMIT = 1_000_000L;

    /**
     * THE PINNED CHAIN · AAPL, 8 expiries x strikes 100..298 step 2 x C/P.
     * Deterministic, so the collision below is a fact about these symbols,
     * not a roll of the dice re-rolled per run.
     */
    static final int[] EXPIRIES = {260821, 260918, 261016, 261120, 261218, 270115, 270219, 270319};

    /** The pair that collides under the OLD constant · both fold to slot
     *  472749 at one million, and to two different slots at a trillion.
     *  Computed from the fold itself and pinned here so the lesson names its
     *  witnesses instead of searching for them. */
    static final String COLLIDER_A = "AAPL260918C00146000";
    static final String COLLIDER_B = "AAPL270319P00192000";

    /** Fixture symbols · a derived row and a planted old-formula row. */
    static final String DERIVED = "CAPW";
    static final String OLDWAY = "CAPOLD";
    /** OLDWAY's slot as the OLD fold produced it · a slot the CURRENT hash
     *  does not produce for this symbol, which is exactly the point. */
    static final long OLDWAY_SLOT = 169_048L;

    static final String[] MINE = {DERIVED, OLDWAY, "CAPBAD", COLLIDER_A, COLLIDER_B};

    @BeforeAll
    static void boot() throws Exception {
        Shards.boot(
                System.getenv().getOrDefault("MINIBANK_SHARD0_URL", "jdbc:postgresql://localhost:5434/minibank"),
                System.getenv().getOrDefault("MINIBANK_SHARD1_URL", "jdbc:postgresql://localhost:5435/minibank"),
                "minibank", "minibank", 5);
        for (Shard s : Shards.all()) s.createSchema();
        Directory.createOwnDatabase();
        Shards.nameRegions("eu", "uk");
        Shards.setResolver(Directory::shardOf);
    }

    @AfterAll
    static void unplug() {
        Shards.setResolver(null);
    }

    @BeforeEach
    void freshWorld() throws Exception {
        Fixtures.resetShards();
        Fixtures.resetDirectory();
        // asset_slots survives the ledger wipe on purpose (it is a listing,
        // not a balance) · so this test removes ITS OWN symbols and no others
        for (Shard s : Shards.all()) {
            try (Connection c = s.open()) {
                deleteMine(c);
            }
        }
        Directory.register(IGOR, "igor", EU);
        Shards.forCustomer(IGOR).createCustomer(IGOR, "igor");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 1: one chain of 1,600 contracts · collides at a million slots, collision-free at a trillion")
    void lesson1_theChainIsTheBlocker() throws Exception {
        // the local fold IS the production fold · pinned first, so the rest
        // of this lesson (and the sibling broker lesson that plants a squat)
        // is measuring the hash the registry actually runs
        for (String probe : new String[]{COLLIDER_A, COLLIDER_B, DERIVED, OLDWAY, "XSP260821C00700000"})
            assertEquals(fold(probe, AssetRegistry.SLOT_LIMIT), AssetRegistry.derivedSlot(probe),
                    "the test's replica of the fold must match derivedSlot, or nothing below measures anything");

        List<String> chain = pinnedChain();
        assertEquals(1600, chain.size(), "8 expiries x 100 strikes x calls and puts");

        // under the OLD constant the pinned pair lands on one slot · this is
        // the recorded blocker, reproduced rather than remembered
        assertEquals(fold(COLLIDER_A, OLD_LIMIT), fold(COLLIDER_B, OLD_LIMIT),
                "these two contracts of one chain shared slot " + fold(COLLIDER_A, OLD_LIMIT)
                        + " at SLOT_LIMIT=1e6 · the second of them could never list");

        // under the CURRENT constant the whole chain derives distinct slots ·
        // REVERTING SLOT_LIMIT TO 1e6 FAILS EXACTLY HERE
        Set<Long> slots = new HashSet<>();
        for (String sym : chain) slots.add(AssetRegistry.derivedSlot(sym));
        assertEquals(chain.size(), slots.size(),
                "every contract of the chain gets its own slot under SLOT_LIMIT=" + AssetRegistry.SLOT_LIMIT);

        // and the ids the widened slots mint stay inside the invariants: above
        // the legacy space, inside the slot, under BIGINT
        long widest = slots.stream().max(Long::compare).orElseThrow();
        long broker = AssetRegistry.ASSET_BASE + widest * AssetRegistry.SLOT_STRIDE + AssetRegistry.SUFFIX_BROKER;
        AssetRegistry.requireAllocatable(broker, "widest");
        assertTrue(broker > 0, "no overflow at the top of the widened range");

        // the pair that could not coexist now lists side by side, on real
        // shards, each with its own accounts
        AssetRegistry.register(COLLIDER_A, COLLIDER_A, "aapl sep 2026 146 call",
                new java.math.BigDecimal("100"), "option", java.time.LocalDate.of(2026, 9, 18));
        AssetRegistry.register(COLLIDER_B, COLLIDER_B, "aapl mar 2027 192 put",
                new java.math.BigDecimal("100"), "option", java.time.LocalDate.of(2027, 3, 19));
        try (Connection c = Shards.s(EU).open()) {
            long a = AssetRegistry.bySymbol(c, COLLIDER_A).holdingFor(IGOR);
            long b = AssetRegistry.bySymbol(c, COLLIDER_B).holdingFor(IGOR);
            assertNotEquals(a, b, "two contracts, two holding accounts · the whole point of the widening");
        }
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 2: the migration, fingerprinted · recorded ids byte-identical before and after, second run a no-op")
    void lesson2_migrationRenumbersNothing() throws Exception {
        // RECONSTRUCT a representative database: the two legacy rows (already
        // seeded by createSchema), one row derived under the current constant,
        // and one row planted with the OLD fold's slot · a slot the current
        // hash does NOT produce for that symbol, so any code path that
        // re-derives instead of reading recorded columns produces different
        // ids and fails the assertions below.
        assertNotEquals(OLDWAY_SLOT, AssetRegistry.derivedSlot(OLDWAY),
                "the planted slot must be one the current hash would not choose, or this proves nothing");
        assertEquals(OLDWAY_SLOT, fold(OLDWAY, OLD_LIMIT), "and it is exactly what the old constant derived");

        AssetRegistry.register(DERIVED, DERIVED, "cap widened");
        long oldwayBroker = AssetRegistry.ASSET_BASE + OLDWAY_SLOT * AssetRegistry.SLOT_STRIDE + AssetRegistry.SUFFIX_BROKER;
        long oldwayClearing = AssetRegistry.ASSET_BASE + OLDWAY_SLOT * AssetRegistry.SLOT_STRIDE + AssetRegistry.SUFFIX_CLEARING;
        long oldwayHolding = AssetRegistry.ASSET_BASE + OLDWAY_SLOT * AssetRegistry.SLOT_STRIDE + IGOR;
        for (Shard s : Shards.all()) {
            try (Connection c = s.open(); PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO asset_slots(symbol, currency, label, slot, legacy_offset,
                                            broker_account, clearing_account, multiplier, kind, expires_on)
                    VALUES (?,?,?,?,NULL,?,?,1,'equity',NULL)""")) {
                ps.setString(1, OLDWAY);
                ps.setString(2, OLDWAY);
                ps.setString(3, "cap oldway");
                ps.setLong(4, OLDWAY_SLOT);
                ps.setLong(5, oldwayBroker);
                ps.setLong(6, oldwayClearing);
                ps.executeUpdate();
            }
        }
        // recorded HOLDINGS, both shapes: one written by the code path, one
        // planted as the old formula wrote it
        Shard home = Shards.forCustomer(IGOR);
        long derivedHolding;
        try (Connection c = home.open()) {
            derivedHolding = AssetRegistry.ensureHolding(c, DERIVED, IGOR);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO asset_accounts(symbol, customer_id, account_id) VALUES (?,?,?)")) {
                ps.setString(1, OLDWAY);
                ps.setLong(2, IGOR);
                ps.setLong(3, oldwayHolding);
                ps.executeUpdate();
            }
        }

        // FINGERPRINT · every recorded id, one hash per shard
        List<String> before = fingerprints();

        // MIGRATE · V10, exactly as Flyway ships it, applied from the file
        String v9 = new String(SlotCapacityLessonTest.class
                .getResourceAsStream("/db/shard/V10__slot_capacity.sql").readAllBytes(), StandardCharsets.UTF_8);
        for (Shard s : Shards.all()) {
            try (Connection c = s.open(); Statement st = c.createStatement()) {
                st.execute(v9);
            }
        }
        assertEquals(before, fingerprints(),
                "THE POINT: the migration renumbers nothing · every recorded id is byte-identical");

        // SECOND RUN A NO-OP · the migration again, and the boot-time
        // re-registration of every symbol, and still not a byte moves
        for (Shard s : Shards.all()) {
            try (Connection c = s.open(); Statement st = c.createStatement()) {
                st.execute(v9);
            }
        }
        AssetRegistry.register(DERIVED, DERIVED, "cap widened");
        AssetRegistry.register(OLDWAY, OLDWAY, "cap oldway");
        assertEquals(before, fingerprints(), "idempotent · the second run changed nothing");

        // RESOLUTION GOES THROUGH RECORDED COLUMNS, not the hash. If any of
        // these re-derived, the old-formula row would answer with the NEW
        // slot's ids and a customer's money would be pointed somewhere else.
        try (Connection c = home.open()) {
            AssetRegistry.Asset oldway = AssetRegistry.bySymbol(c, OLDWAY);
            assertEquals(OLDWAY_SLOT, oldway.slot(), "the recorded slot is the slot");
            assertEquals(oldwayBroker, oldway.brokerAccount());
            assertEquals(oldwayClearing, oldway.clearingAccount());
            assertEquals(oldwayHolding, oldway.holdingFor(IGOR),
                    "holdingFor computes from the RECORDED slot · the current hash would say "
                            + (AssetRegistry.ASSET_BASE + AssetRegistry.derivedSlot(OLDWAY)
                                    * AssetRegistry.SLOT_STRIDE + IGOR));
            assertEquals(oldwayHolding, AssetRegistry.ensureHolding(c, OLDWAY, IGOR),
                    "and the recorded asset_accounts row wins over any derivation");
            assertEquals(derivedHolding, AssetRegistry.ensureHolding(c, DERIVED, IGOR),
                    "the derived row's recorded holding is equally immovable");
        }
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 3: the arithmetic is a CONSTRAINT · accounts that disagree with their slot are refused by the database")
    void lesson3_theCheckRefusesDrift() throws Exception {
        try (Connection c = Shards.s(EU).open()) {
            SQLException boom = assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO asset_slots(symbol, currency, label, slot, legacy_offset,
                                                broker_account, clearing_account, multiplier, kind, expires_on)
                        VALUES ('CAPBAD','CAPBAD','cap bad', 777, NULL, ?, ?, 1, 'equity', NULL)""")) {
                    // broker account off by one from what slot 777 derives
                    ps.setLong(1, AssetRegistry.ASSET_BASE + 777 * AssetRegistry.SLOT_STRIDE + 2);
                    ps.setLong(2, AssetRegistry.ASSET_BASE + 777 * AssetRegistry.SLOT_STRIDE + AssetRegistry.SUFFIX_CLEARING);
                    ps.executeUpdate();
                }
            }, "a register() whose arithmetic drifted from its recorded history must fail in the database");
            assertEquals("23514", boom.getSQLState(), "a CHECK violation, by name");

            // and the ceiling is part of the same constraint · a slot at or
            // above the limit cannot be recorded at all
            SQLException over = assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO asset_slots(symbol, currency, label, slot, legacy_offset,
                                                broker_account, clearing_account, multiplier, kind, expires_on)
                        VALUES ('CAPBAD','CAPBAD','cap bad', ?, NULL, ?, ?, 1, 'equity', NULL)""")) {
                    long slot = 1_000_000_000_000L;
                    ps.setLong(1, slot);
                    ps.setLong(2, AssetRegistry.ASSET_BASE + slot * AssetRegistry.SLOT_STRIDE + AssetRegistry.SUFFIX_BROKER);
                    ps.setLong(3, AssetRegistry.ASSET_BASE + slot * AssetRegistry.SLOT_STRIDE + AssetRegistry.SUFFIX_CLEARING);
                    ps.executeUpdate();
                }
            });
            assertEquals("23514", over.getSQLState());
        }
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 4: the legacy pins hold against the new constant · nothing that predates the registry moved")
    void lesson4_legacyPinsHold() throws Exception {
        try (Connection c = Shards.s(EU).open()) {
            AssetRegistry.Asset btc = AssetRegistry.bySymbol(c, "btc");
            AssetRegistry.Asset aapl = AssetRegistry.bySymbol(c, "AAPL");
            assertEquals(IGOR + Products.BTC, btc.holdingFor(IGOR), "bitcoin is still customerId+200");
            assertEquals(IGOR + Products.AAPL, aapl.holdingFor(IGOR), "apple still customerId+300");
            assertEquals(Shard.BROKER_BTC, btc.brokerAccount(), "the broker's BTC leg is still 5");
            assertEquals(Shard.BROKER_AAPL, aapl.brokerAccount(), "and its AAPL leg still 6");
            assertEquals(Shard.IN_TRANSIT_BTC, btc.clearingAccount(), "BTC still clears through 8");
            assertEquals(Shard.IN_TRANSIT_AAPL, aapl.clearingAccount(), "AAPL through 9");
        }
        // the old floor of the derivable range did not move either · slots 0
        // and 1 stay reserved, and an id in the legacy space stays refused
        assertTrue(AssetRegistry.derivedSlot("anything") >= AssetRegistry.FIRST_DERIVED_SLOT);
        assertThrows(IllegalStateException.class,
                () -> AssetRegistry.requireAllocatable(699, "NOPE"));
    }

    // ------------------------------------------------------------------ helpers

    /** The registry's own fold, replicated · FNV-1a over the normalized
     *  symbol, folded into [FIRST_DERIVED_SLOT, limit). Lesson 1 asserts this
     *  replica equals derivedSlot at the current limit before using it. */
    private static long fold(String symbol, long limit) {
        long h = 0xcbf29ce484222325L;
        for (byte b : symbol.trim().toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xffL);
            h *= 0x100000001b3L;
        }
        return Math.floorMod(h, limit - AssetRegistry.FIRST_DERIVED_SLOT) + AssetRegistry.FIRST_DERIVED_SLOT;
    }

    private static List<String> pinnedChain() {
        List<String> out = new ArrayList<>(1600);
        for (int expiry : EXPIRIES)
            for (int strike = 100; strike < 300; strike += 2)
                for (char cp : new char[]{'C', 'P'})
                    out.add(String.format("AAPL%06d%c%08d", expiry, cp, strike * 1000));
        return out;
    }

    /** md5 over every recorded id on every shard, in a fixed order · the
     *  fingerprint the migration must not change by a byte. */
    private static List<String> fingerprints() throws SQLException {
        List<String> out = new ArrayList<>();
        for (Shard s : Shards.all()) {
            try (Connection c = s.open()) {
                out.add(one(c, """
                        SELECT md5(coalesce(string_agg(
                                 symbol || '|' || currency || '|' || slot
                                 || '|' || coalesce(legacy_offset::text, '-')
                                 || '|' || broker_account || '|' || clearing_account
                                 || '|' || multiplier || '|' || kind
                                 || '|' || coalesce(expires_on::text, '-'),
                                 E'\\n' ORDER BY symbol), ''))
                        FROM asset_slots""")
                        + '/' + one(c, """
                        SELECT md5(coalesce(string_agg(
                                 symbol || '|' || customer_id || '|' || account_id,
                                 E'\\n' ORDER BY symbol, customer_id), ''))
                        FROM asset_accounts"""));
            }
        }
        return out;
    }

    private static String one(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static void deleteMine(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM asset_accounts WHERE symbol = ANY(?)")) {
            ps.setArray(1, c.createArrayOf("text", MINE));
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM asset_slots WHERE symbol = ANY(?)")) {
            ps.setArray(1, c.createArrayOf("text", MINE));
            ps.executeUpdate();
        }
    }
}
