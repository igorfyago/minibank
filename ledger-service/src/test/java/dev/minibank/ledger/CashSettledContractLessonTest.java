package dev.minibank.ledger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * XSP SETTLES CASH · a contract position is only ever bought and sold for
 * euros, and the ledger already speaks that language.
 *
 * XSP options are European and cash-settled: exercise never delivers shares,
 * it delivers money. For the LEDGER that means an XSP contract position has
 * exactly two legs its whole life · a EUR cash leg and a contract leg
 * denominated in the contract's own currency (the OCC symbol) · and there is
 * no share currency anywhere for any code path to invent. settleFill was
 * built for "units of an asset against cash" without asking what the asset
 * is, and these lessons prove that a multiplier-100 contract needs nothing
 * more from it:
 *
 *   lesson 1  a buy settles 2 CONTRACTS against qty*premium*100 euros · the
 *             units are contracts (2, never 200: the multiplier lives in the
 *             CASH number the broker computed, not in the units), and every
 *             currency sums to zero on its own
 *   lesson 2  selling to flat returns the cash and empties the contract
 *             currency column entirely · sum-zero holds at every step
 *   lesson 3  overselling is refused · no naked short through settlement
 *   lesson 4  a redelivered fill settles once · the fill id is the gate
 *
 * OUT OF SCOPE, SAID PLAINLY: the EXPIRY SWEEP. When an XSP contract
 * expires, a real clearing house settles it to cash at a settlement price
 * and removes the position. No such mechanism exists in this codebase: an
 * expired position stays on the books, unvalued (the read paths refuse to
 * price it) and untradable (the order gates refuse it), until an expiry
 * settlement is built as its own deliberate act with an observed settlement
 * price. Nothing in this lesson pretends otherwise, and nothing here writes
 * a zero where that unobserved price would go.
 *
 * Requires: docker compose up -d   (shards :5434/:5435, directory on :5433)
 */
class CashSettledContractLessonTest {

    static final long IGOR = 10;
    static final int EU = 0;
    static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

    /** A real-shaped XSP contract, expiring safely in the future. */
    static final LocalDate EXPIRY = LocalDate.now(ZoneOffset.UTC).plusMonths(6);
    static final String XSP_CALL = "XSP" + EXPIRY.format(YYMMDD) + "C00700000";

    static final BigDecimal HUNDRED = new BigDecimal("100");
    /** 2 contracts at premium 3.10 · the broker's consideration:
     *  2 * 3.10 * 100 = 620.00. The multiplier is IN this number. */
    static final BigDecimal TWO = new BigDecimal("2");
    static final BigDecimal BUY_CASH = new BigDecimal("620.00");
    static final BigDecimal SELL_CASH = new BigDecimal("700.00");

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
        for (Shard s : Shards.all()) {
            try (Connection c = s.open()) {
                try (var ps = c.prepareStatement("DELETE FROM asset_accounts WHERE symbol = ?")) {
                    ps.setString(1, XSP_CALL);
                    ps.executeUpdate();
                }
                try (var ps = c.prepareStatement("DELETE FROM asset_slots WHERE symbol = ?")) {
                    ps.setString(1, XSP_CALL);
                    ps.executeUpdate();
                }
            }
        }
        AssetRegistry.register(XSP_CALL, XSP_CALL, "xsp " + EXPIRY + " 700 call",
                HUNDRED, "option", EXPIRY);
        Directory.register(IGOR, "igor", EU);
        Shards.forCustomer(IGOR).createCustomer(IGOR, "igor");
        Shards.forCustomer(IGOR).transferLocal(UUID.randomUUID(), Shard.WORLD, IGOR, new BigDecimal("2000.00"));
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 1: a buy settles CONTRACTS against cash · units are contracts, the multiplier lives in the money")
    void lesson1_buySettlesContractsAgainstCash() throws Exception {
        Shard home = Shards.forCustomer(IGOR);
        long holding, brokerLeg;
        try (Connection c = home.open()) {
            AssetRegistry.Asset a = AssetRegistry.bySymbol(c, XSP_CALL);
            assertEquals(0, HUNDRED.compareTo(a.multiplier()),
                    "the registry records the contract size, so valuation can honour it");
            brokerLeg = a.brokerAccount();
            assertEquals(Ledger.Ok.class,
                    Products.settleFill(UUID.randomUUID(), IGOR, XSP_CALL, true, TWO, BUY_CASH).getClass());
            holding = AssetRegistry.bySymbol(c, XSP_CALL).holdingFor(IGOR);
        }

        assertEquals(0, TWO.compareTo(home.balance(holding)),
                "THE POINT: the ledger holds 2 CONTRACTS, not 200 shares · the multiplier scaled "
                        + "the cash the broker computed (2 * 3.10 * 100 = 620.00) and never the units, "
                        + "which is what keeps Reconciliation's quantity invariant true");
        assertEquals(0, TWO.negate().compareTo(home.balance(brokerLeg)),
                "the broker's contract leg mirrors it exactly");
        assertEquals(0, new BigDecimal("1380.00").compareTo(home.balance(IGOR)),
                "620 euros left the customer · the only other thing that moved");
        assertEquals(0, sumZero(home).size(),
                "and every currency sums to zero ON ITS OWN · EUR balances against EUR, "
                        + XSP_CALL + " against " + XSP_CALL + ", no cross-subsidy possible");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 2: selling to flat returns cash and empties the contract column · cash in, cash out, nothing else ever")
    void lesson2_sellToFlatIsPureCash() throws Exception {
        Shard home = Shards.forCustomer(IGOR);
        Products.settleFill(UUID.randomUUID(), IGOR, XSP_CALL, true, TWO, BUY_CASH);
        Products.settleFill(UUID.randomUUID(), IGOR, XSP_CALL, false, TWO, SELL_CASH);

        long holding, brokerLeg;
        try (Connection c = home.open()) {
            AssetRegistry.Asset a = AssetRegistry.bySymbol(c, XSP_CALL);
            holding = a.holdingFor(IGOR);
            brokerLeg = a.brokerAccount();
        }
        assertEquals(0, BigDecimal.ZERO.compareTo(home.balance(holding)), "flat");
        assertEquals(0, BigDecimal.ZERO.compareTo(home.balance(brokerLeg)),
                "the contract currency's column is EMPTY end to end · the position's whole life "
                        + "was euros against contracts, no share leg existed at any moment");
        assertEquals(0, new BigDecimal("2080.00").compareTo(home.balance(IGOR)),
                "2000 - 620 + 700 · the round trip in cash, to the cent");
        assertEquals(0, sumZero(home).size(), "per-currency sum-zero held through both legs");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 3: overselling is refused · settlement cannot mint a naked short")
    void lesson3_oversellRefused() throws Exception {
        Shard home = Shards.forCustomer(IGOR);
        Products.settleFill(UUID.randomUUID(), IGOR, XSP_CALL, true, TWO, BUY_CASH);

        assertInstanceOf(Ledger.InsufficientFunds.class,
                Products.settleFill(UUID.randomUUID(), IGOR, XSP_CALL, false,
                        new BigDecimal("3"), new BigDecimal("1050.00")),
                "selling 3 against a holding of 2 is refused, not partially honoured");

        long holding;
        try (Connection c = home.open()) {
            holding = AssetRegistry.bySymbol(c, XSP_CALL).holdingFor(IGOR);
        }
        assertEquals(0, TWO.compareTo(home.balance(holding)), "the position is untouched");
        assertEquals(0, new BigDecimal("1380.00").compareTo(home.balance(IGOR)), "and so is the cash");
        assertEquals(0, sumZero(home).size());
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 4: a redelivered fill settles once · the fill id is the gate, for contracts as for everything")
    void lesson4_redeliverySettlesOnce() throws Exception {
        Shard home = Shards.forCustomer(IGOR);
        UUID fillId = UUID.randomUUID();
        assertInstanceOf(Ledger.Ok.class,
                Products.settleFill(fillId, IGOR, XSP_CALL, true, TWO, BUY_CASH));
        assertInstanceOf(Ledger.AlreadyProcessed.class,
                Products.settleFill(fillId, IGOR, XSP_CALL, true, TWO, BUY_CASH),
                "Kafka will redeliver eventually · the transactions table already knows");
        assertEquals(0, new BigDecimal("1380.00").compareTo(home.balance(IGOR)),
                "620 left once, not twice");
        assertEquals(0, sumZero(home).size());
    }

    // ------------------------------------------------------------------
    private static List<UUID> sumZero(Shard s) throws Exception {
        try (Connection c = s.open()) {
            return Ledger.sumZeroViolationsOn(c);
        }
    }
}
