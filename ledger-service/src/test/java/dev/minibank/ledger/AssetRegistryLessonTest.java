package dev.minibank.ledger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE ASSET REGISTRY · a third instrument, and the four ways it could have
 * gone wrong.
 *
 * The bank used to answer "which account holds this position?" with
 *
 *     assetAcct = customerId + ("btc".equals(asset) ? BTC : AAPL)
 *
 * The else-branch is the bug: every symbol that was not bitcoin landed in
 * the customer's APPLE account, and the per-currency sum-zero audit passed
 * anyway, on both shards, every time. A wrong holding that survives every
 * check the bank runs is worse than a crash, and the only thing preventing
 * it in production was that the broker's catalog had exactly two rows.
 *
 *   lesson 1  the legacy ids DID NOT MOVE · bitcoin is still customerId+200
 *             and apple still customerId+300, in the registry and on disk
 *   lesson 2  a third instrument gets its OWN account, nowhere near apple's,
 *             and nowhere near any id the old scheme can produce
 *   lesson 3  an unlisted symbol RAISES · there is no else-branch left to
 *             fall into, which is the actual fix
 *   lesson 4  RELOCATION MOVES IT TOO · a registry-allocated holding is not
 *             in Products.OFFSETS, and a shelf walk that only knew about
 *             OFFSETS would strand it on the old shard
 *   lesson 5  and so does the repair, for a shelf an older build left behind
 *   lesson 6  the id scheme cannot collide with the legacy one, and the
 *             slot derivation is stable across processes
 *
 * Requires: docker compose up -d   (shards :5434/:5435, directory on :5433)
 */
class AssetRegistryLessonTest {

    static final long IGOR = 10;
    static final int EU = 0, UK = 1;

    /** The third instrument. Listed HERE and not in Catalog.seed(): making a
     *  third instrument possible is what this change is; deciding what the
     *  bank actually lists is a business decision, not a test's to make. */
    static final String MSFT = "MSFT";

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
        AssetRegistry.register(MSFT, MSFT, "microsoft");

        Directory.register(IGOR, "igor", EU);
        Shards.forCustomer(IGOR).createCustomer(IGOR, "igor");
        Shards.forCustomer(IGOR).transferLocal(UUID.randomUUID(), Shard.WORLD, IGOR, eur("2000.00"));
        Products.ensureFor(IGOR);
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 1: nothing already in the database moved · bitcoin is still +200, apple still +300")
    void lesson1_legacyIdsAreUnchanged() throws Exception {
        Shard home = Shards.forCustomer(IGOR);
        try (Connection c = home.open()) {
            AssetRegistry.Asset btc = AssetRegistry.bySymbol(c, "btc");
            AssetRegistry.Asset aapl = AssetRegistry.bySymbol(c, "AAPL");

            assertEquals(IGOR + Products.BTC, btc.holdingFor(IGOR), "bitcoin keeps the offset it has had all along");
            assertEquals(IGOR + Products.AAPL, aapl.holdingFor(IGOR), "and so does apple");
            assertEquals(Shard.BROKER_BTC, btc.brokerAccount(), "the broker's BTC leg is still account 5");
            assertEquals(Shard.BROKER_AAPL, aapl.brokerAccount(), "and its AAPL leg still account 6");
            assertEquals(Shard.IN_TRANSIT_BTC, btc.clearingAccount(), "BTC still clears through 8");
            assertEquals(Shard.IN_TRANSIT_AAPL, aapl.clearingAccount(), "AAPL still through 9");
        }

        // and the money agrees with the table: a trade placed through the
        // registry lands in exactly the account the ternary used to pick
        Products.tradeWithoutBroker(UUID.randomUUID(), IGOR, "btc", true, eur("100.00"), eur("50000.00"));
        Products.tradeWithoutBroker(UUID.randomUUID(), IGOR, "aapl", true, eur("200.00"), eur("250.00"));
        assertEquals(0, eur("0.002").compareTo(home.balance(IGOR + Products.BTC)), "bitcoin, in 210");
        assertEquals(0, eur("0.8").compareTo(home.balance(IGOR + Products.AAPL)), "apple, in 310");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 2: THE BUG · a third instrument lands in its OWN account, not in apple's")
    void lesson2_thirdInstrumentHasItsOwnAccount() throws Exception {
        Shard home = Shards.forCustomer(IGOR);
        long msftAcct;
        try (Connection c = home.open()) {
            msftAcct = AssetRegistry.bySymbol(c, MSFT).holdingFor(IGOR);
        }

        // the account does not exist yet · holdings are allocated LAZILY, and
        // a customer who has never touched MSFT does not hold MSFT
        assertFalse(home.hasAccount(msftAcct), "no trade, no account");

        Products.tradeWithoutBroker(UUID.randomUUID(), IGOR, MSFT, true, eur("400.00"), eur("400.00"));

        assertNotEquals(IGOR + Products.AAPL, msftAcct, "THE bug: this used to be apple's account");
        assertNotEquals(IGOR + Products.BTC, msftAcct);
        assertTrue(msftAcct >= AssetRegistry.ASSET_BASE,
                "and it lives in the asset range, above every id the legacy scheme can produce");

        assertTrue(home.hasAccount(msftAcct), "the first trade created it");
        assertEquals(0, eur("1.0").compareTo(home.balance(msftAcct)), "one share of microsoft, in its own account");
        assertEquals(0, BigDecimal.ZERO.compareTo(home.balance(IGOR + Products.AAPL)),
                "and APPLE IS UNTOUCHED · this is the whole lesson");
        assertEquals(0, BigDecimal.ZERO.compareTo(home.balance(IGOR + Products.BTC)), "bitcoin too");
        assertEquals(0, eur("1600.00").compareTo(home.balance(IGOR)), "the cash left the customer once");
        assertEquals(0, sumZero(home).size(), "and every currency sums to zero on its own, MSFT included");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 3: an unlisted symbol RAISES · no default branch means no wrong holding")
    void lesson3_unlistedSymbolFailsClosed() throws Exception {
        Shard home = Shards.forCustomer(IGOR);

        assertThrows(AssetRegistry.UnknownAsset.class,
                () -> Products.tradeWithoutBroker(UUID.randomUUID(), IGOR, "TSLA", true, eur("100.00"), eur("200.00")),
                "an instrument nobody listed has no account · say so, do not pick apple's");
        assertThrows(AssetRegistry.UnknownAsset.class,
                () -> Products.settleFill(UUID.randomUUID(), IGOR, "tsla", true, eur("1"), eur("200.00")),
                "and the settlement path refuses it too · it used to be the one that did not check");

        assertEquals(0, BigDecimal.ZERO.compareTo(home.balance(IGOR + Products.AAPL)),
                "apple holds nothing it was never bought");
        assertEquals(0, eur("2000.00").compareTo(home.balance(IGOR)), "and not a cent moved");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 4: all three travel · a registry holding is not in OFFSETS, and must move anyway")
    void lesson4_relocationMovesEveryAssetAccount() throws Exception {
        Shard eu = Shards.s(EU), uk = Shards.s(UK);
        Products.tradeWithoutBroker(UUID.randomUUID(), IGOR, "btc", true, eur("100.00"), eur("50000.00"));
        Products.tradeWithoutBroker(UUID.randomUUID(), IGOR, "aapl", true, eur("200.00"), eur("250.00"));
        Products.tradeWithoutBroker(UUID.randomUUID(), IGOR, MSFT, true, eur("400.00"), eur("400.00"));

        long msftAcct;
        try (Connection c = eu.open()) {
            msftAcct = AssetRegistry.bySymbol(c, MSFT).holdingFor(IGOR);
        }
        BigDecimal btcBefore = eu.balance(IGOR + Products.BTC);
        BigDecimal aaplBefore = eu.balance(IGOR + Products.AAPL);
        BigDecimal msftBefore = eu.balance(msftAcct);
        BigDecimal mainBefore = eu.balance(IGOR);
        assertTrue(btcBefore.signum() > 0 && aaplBefore.signum() > 0 && msftBefore.signum() > 0,
                "three holdings, three currencies, all funded");

        Relocation.relocate(IGOR, UK);

        // THE assertion this whole change exists for: the account with no
        // fixed offset travelled with the ones that have one
        assertTrue(uk.hasAccount(msftAcct), "the MSFT holding must exist in the new region");
        assertEquals(0, msftBefore.compareTo(uk.balance(msftAcct)), "and arrive to the last decimal");
        assertEquals(0, BigDecimal.ZERO.compareTo(eu.balance(msftAcct)), "leaving nothing on the old soil");
        assertEquals(0, btcBefore.compareTo(uk.balance(IGOR + Products.BTC)), "bitcoin came too");
        assertEquals(0, aaplBefore.compareTo(uk.balance(IGOR + Products.AAPL)), "and apple");
        assertEquals(0, mainBefore.compareTo(uk.balance(IGOR)), "and the cash");

        // per-currency sum zero on BOTH shards · had MSFT ridden the EUR
        // clearing account, this is the assertion that would have caught it
        assertEquals(0, sumZero(eu).size(), "eu books balance, per currency");
        assertEquals(0, sumZero(uk).size(), "uk books balance, per currency");

        // and every clearing account drains fleet-wide, MSFT's included
        try (Connection c = uk.open()) {
            for (AssetRegistry.Asset a : AssetRegistry.all(c)) {
                BigDecimal pipe = BigDecimal.ZERO;
                for (Shard s : Shards.all()) pipe = pipe.add(s.balance(a.clearingAccount()));
                assertEquals(0, BigDecimal.ZERO.compareTo(pipe),
                        a.symbol() + " must settle to zero across the fleet");
            }
        }
        assertEquals(0, BigDecimal.ZERO.compareTo(Shards.inFlight()), "and the EUR pipe is empty");

        // routing followed the money · a uk customer's MSFT must not route to eu
        assertEquals(UK, Shards.forCustomer(msftAcct).index, "residency is not partial");

        // and the holding still works in the new region · the reported bug,
        // in its new form
        Products.tradeWithoutBroker(UUID.randomUUID(), IGOR, MSFT, true, eur("100.00"), eur("400.00"));
        assertEquals(0, msftBefore.add(eur("0.25")).compareTo(uk.balance(msftAcct)),
                "buying more microsoft must work after a move");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 5: repair · a stranded registry-allocated holding comes home too")
    void lesson5_repairBringsAStrandedHoldingHome() throws Exception {
        Shard eu = Shards.s(EU), uk = Shards.s(UK);
        Products.tradeWithoutBroker(UUID.randomUUID(), IGOR, MSFT, true, eur("400.00"), eur("400.00"));
        long msftAcct;
        try (Connection c = eu.open()) {
            msftAcct = AssetRegistry.bySymbol(c, MSFT).holdingFor(IGOR);
        }
        BigDecimal msft = eu.balance(msftAcct);
        assertTrue(msft.signum() > 0, "there is a real holding to strand");

        // reproduce what a build that only knew about OFFSETS would do: move
        // the main account, flip the pointer, leave the holding behind
        Directory.setMoving(IGOR, true);
        BigDecimal main = eu.balance(IGOR);
        UUID tx = UUID.randomUUID();
        eu.depart(tx, IGOR, IGOR, main);
        uk.createCustomer(IGOR, "igor");
        uk.arrive(tx, IGOR, main);
        Directory.flip(IGOR, UK);

        assertEquals(UK, Shards.forCustomer(IGOR).index);
        assertFalse(uk.hasAccount(msftAcct), "the stranded state: microsoft on the wrong soil");

        assertTrue(Relocation.repairShelves() > 0, "the repair finds work to do");

        assertEquals(0, msft.compareTo(uk.balance(msftAcct)), "the holding came home");
        assertEquals(0, BigDecimal.ZERO.compareTo(eu.balance(msftAcct)), "and left eu empty");
        assertEquals(UK, Shards.forCustomer(msftAcct).index, "routing came home too");
        assertEquals(0, sumZero(eu).size());
        assertEquals(0, sumZero(uk).size());

        assertEquals(0, Relocation.repairShelves(), "and the second run is a no-op");
        assertEquals(0, msft.compareTo(uk.balance(msftAcct)), "it moved nothing twice");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 6: the new id space cannot reach the old one, and the slot is the same everywhere")
    void lesson6_theIdSchemeIsCheckedNotHoped() throws Exception {
        // the slot is derived, not sequenced · two shards reach the same id
        // for the same symbol without talking to each other, which is why
        // this is a hash and not a MAX(slot)+1
        assertEquals(AssetRegistry.derivedSlot(MSFT), AssetRegistry.derivedSlot("msft"),
                "case is not part of a ticker");
        try (Connection a = Shards.s(EU).open(); Connection b = Shards.s(UK).open()) {
            assertEquals(AssetRegistry.bySymbol(a, MSFT).holdingFor(IGOR),
                    AssetRegistry.bySymbol(b, MSFT).holdingFor(IGOR),
                    "both shards must agree on where this customer's microsoft lives");
        }
        assertTrue(AssetRegistry.derivedSlot(MSFT) >= AssetRegistry.FIRST_DERIVED_SLOT,
                "slots 0 and 1 belong to bitcoin and apple and are never derived");

        // no derived id can land in the legacy space · not for any customer
        // the bank can mint (Directory and HttpApi both cap customers at 99)
        try (Connection c = Shards.s(EU).open()) {
            for (AssetRegistry.Asset asset : AssetRegistry.all(c)) {
                if (asset.legacyOffset() != null) continue;
                for (long customer = Shards.FIRST_CUSTOMER_ID; customer < 100; customer++) {
                    long id = asset.holdingFor(customer);
                    assertTrue(id >= AssetRegistry.ASSET_BASE,
                            "asset account " + id + " would collide with the legacy scheme");
                }
                assertTrue(asset.brokerAccount() >= AssetRegistry.ASSET_BASE);
                assertTrue(asset.clearingAccount() >= AssetRegistry.ASSET_BASE);
                // the clearing account sorts BELOW every holding in its slot,
                // so the bank's ascending-lock rule keeps holding here too
                assertTrue(asset.clearingAccount() < asset.holdingFor(Shards.FIRST_CUSTOMER_ID),
                        "a slot's clearing account must lock before its holdings");
            }
        }

        // and the check is in code, not in hope
        assertThrows(IllegalStateException.class,
                () -> AssetRegistry.requireAllocatable(699, "NOPE"),
                "an id inside the legacy shelf range must be refused outright");
    }

    // ------------------------------------------------------------------
    private static BigDecimal eur(String v) {
        return new BigDecimal(v);
    }

    private static List<UUID> sumZero(Shard s) throws Exception {
        try (Connection c = s.open()) {
            return Ledger.sumZeroViolationsOn(c);
        }
    }
}
