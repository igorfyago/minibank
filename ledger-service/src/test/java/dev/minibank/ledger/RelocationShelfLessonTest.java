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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STAGE 6b · A CUSTOMER IS NOT ONE ACCOUNT.
 *
 * Relocation used to move the main balance and nothing else, so a customer
 * who changed region left their savings, card debt, loan and assets behind
 * on the old machine. The app read the new home, found no product accounts,
 * and rendered every product as "€0.00" · money that still existed, on soil
 * the customer no longer lived on, invisible to its owner. The first write
 * that touched a product then failed with "no such account: <id+300>".
 *
 *   lesson 1  the whole SHELF travels · every product account, exactly
 *   lesson 2  and so does the ROUTING · half a flipped directory is a
 *             customer whose savings live abroad
 *   lesson 3  products still WORK after a move · the reported bug
 *   lesson 4  sign and currency generality: a card DEBT travels the other
 *             way, and BTC rides a BTC clearing account so the per-currency
 *             sum-zero audit passes on both shards at every instant
 *   lesson 5  nothing is created or destroyed by moving house
 *   lesson 6  repair: a shelf stranded by the old build comes home, and
 *             running the repair again does nothing
 *   lesson 7  an absent account is an ERROR, not a zero balance
 *
 * Requires: docker compose up -d   (shards :5434/:5435, directory on :5433)
 */
class RelocationShelfLessonTest {

    static final long IGOR = 10, COCO = 11;
    static final int EU = 0, UK = 1;

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
    static void unplug() throws Exception {
        Shards.setResolver(null);   // stage-5 lessons keep their arithmetic router
        // AND put the routing table back. This class is the one that MOVES a
        // customer between regions, so it is the one that leaves the directory
        // saying igor lives in uk with a shelf routed there. Unsetting the
        // resolver hides that from tests that route arithmetically, but any
        // later test that consults the directory inherits a customer who
        // relocated during somebody else's lesson · which is how
        // SettlementSagaLessonTest.lesson6 came to look for a rejection event
        // on a shard the rejection was never written to. A test that mutates
        // global routing owns cleaning it up.
        Fixtures.resetDirectory();
    }

    /** igor lives in eu with a FULL shelf: cash, savings, bitcoin, apple,
     *  a card he has spent on, a loan he has drawn, and a live card hold. */
    @BeforeEach
    void freshWorldWithAShelf() throws Exception {
        Fixtures.resetShards();
        Fixtures.resetDirectory();

        Directory.register(IGOR, "igor", EU);
        Directory.register(COCO, "coco", UK);
        Shards.forCustomer(IGOR).createCustomer(IGOR, "igor");
        Shards.forCustomer(COCO).createCustomer(COCO, "coco");
        Shards.forCustomer(IGOR).transferLocal(UUID.randomUUID(), Shard.WORLD, IGOR, eur("1000.00"));
        Products.ensureFor(IGOR);
        Products.ensureFor(COCO);

        Shard home = Shards.forCustomer(IGOR);
        // savings: a plain transfer between his own accounts
        home.transferLocal(UUID.randomUUID(), IGOR, IGOR + Products.SAVINGS, eur("150.00"));
        // assets: two currencies on the books
        Products.tradeWithoutBroker(UUID.randomUUID(), IGOR, "btc", true, eur("100.00"), eur("50000.00"));
        Products.tradeWithoutBroker(UUID.randomUUID(), IGOR, "aapl", true, eur("200.00"), eur("250.00"));
        // a card debt · the balance that travels the OTHER way
        home.transferLocal(UUID.randomUUID(), IGOR + Products.CARD, Shard.CAFE, eur("75.00"));
        // a loan drawn down · a bigger liability
        Products.mortgage(UUID.randomUUID(), IGOR, eur("5000.00"));
        // a live authorization sitting in the holds account
        Products.authorize(UUID.randomUUID(), IGOR, eur("25.00"));
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 1: the whole shelf travels · every product account arrives, to the cent")
    void lesson1_theShelfTravels() throws Exception {
        Shard eu = Shards.s(EU), uk = Shards.s(UK);
        BigDecimal[] before = new BigDecimal[Products.OFFSETS.length];
        for (int i = 0; i < Products.OFFSETS.length; i++) before[i] = eu.balance(IGOR + Products.OFFSETS[i]);

        // sanity: the fixture really did put money on the shelf
        assertEquals(0, eur("150.00").compareTo(before[0]), "savings funded");
        assertTrue(before[3].signum() < 0, "the card carries a debt");
        assertTrue(before[4].signum() < 0, "the loan is drawn");

        Relocation.relocate(IGOR, UK);

        for (int i = 0; i < Products.OFFSETS.length; i++) {
            long acct = IGOR + Products.OFFSETS[i];
            assertTrue(uk.hasAccount(acct), "account " + acct + " must exist in the new region");
            assertEquals(0, before[i].compareTo(uk.balance(acct)),
                    "account " + acct + " must arrive with its exact balance");
            assertEquals(0, BigDecimal.ZERO.compareTo(eu.balance(acct)),
                    "account " + acct + " must be emptied at the old home");
        }
        assertEquals(0, eur("1000.00").subtract(eur("150.00"))
                        .subtract(eur("100.00")).subtract(eur("200.00")).add(eur("5000.00"))
                        .compareTo(uk.balance(IGOR)),
                "and the main account too");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 2: the routing moves with the money · a uk customer's savings must not route to eu")
    void lesson2_theRoutingMovesToo() throws Exception {
        Relocation.relocate(IGOR, UK);

        assertEquals(UK, Shards.forCustomer(IGOR).index, "the customer routes to uk");
        for (long off : Products.OFFSETS) {
            assertEquals(UK, Shards.forCustomer(IGOR + off).index,
                    "product " + off + " must route to uk as well · residency is not partial");
        }
        // the router and the data now agree: moving to savings is a LOCAL
        // transfer again, not an accidental cross-region saga
        assertFalse(Shards.plan(IGOR, IGOR + Products.SAVINGS).crossShard(),
                "your own savings are never a cross-region payment");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 3: THE BUG · after a move, a product write used to die with 'no such account'")
    void lesson3_productsStillWorkAfterAMove() throws Exception {
        Relocation.relocate(IGOR, UK);

        // this is the exact failure the app reported: buying apple stock
        // looked up account <customer>+300 on the new home and found nothing
        assertInstanceOf(Ledger.Ok.class,
                Products.tradeWithoutBroker(UUID.randomUUID(), IGOR, "aapl", true, eur("50.00"), eur("250.00")),
                "buying apple stock must work in the new region");
        assertInstanceOf(Ledger.Ok.class,
                Products.tradeWithoutBroker(UUID.randomUUID(), IGOR, "btc", true, eur("50.00"), eur("50000.00")));
        assertInstanceOf(Ledger.Ok.class,
                Shards.forCustomer(IGOR).transferLocal(
                        UUID.randomUUID(), IGOR, IGOR + Products.SAVINGS, eur("25.00")),
                "moving to savings must work");
        assertInstanceOf(Ledger.Ok.class,
                Products.mortgage(UUID.randomUUID(), IGOR, eur("1000.00")),
                "borrowing must work");
        assertInstanceOf(Ledger.Ok.class,
                Shards.forCustomer(IGOR).transferLocal(
                        UUID.randomUUID(), IGOR + Products.CARD, Shard.CAFE, eur("10.00")),
                "the card must work");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 4: sign and currency · a debt travels the other way, and BTC needs a BTC clearing account")
    void lesson4_signAndCurrency() throws Exception {
        Shard eu = Shards.s(EU), uk = Shards.s(UK);
        BigDecimal btcBefore = eu.balance(IGOR + Products.BTC);
        BigDecimal cardBefore = eu.balance(IGOR + Products.CARD);
        assertTrue(btcBefore.signum() > 0 && cardBefore.signum() < 0, "one of each sign on the shelf");

        Relocation.relocate(IGOR, UK);

        assertEquals(0, btcBefore.compareTo(uk.balance(IGOR + Products.BTC)), "the bitcoin arrived, in BTC");
        assertEquals(0, cardBefore.compareTo(uk.balance(IGOR + Products.CARD)), "the debt arrived, still negative");

        // THE invariant that forces a clearing account per currency: had BTC
        // travelled through the EUR clearing account, this tx would sum to
        // zero overall and to NON-zero in each currency alone.
        assertEquals(0, sumZero(eu).size(), "eu books balance, per currency");
        assertEquals(0, sumZero(uk).size(), "uk books balance, per currency");

        // every clearing account drains FLEET-WIDE · a clearing balance is
        // positive where money left and negative where it landed, so the
        // pipe is empty when the pair cancels. Per shard it is meant to be
        // lopsided; per fleet it must be zero, in every currency.
        for (long clearing : new long[]{Shard.IN_TRANSIT, Shard.IN_TRANSIT_BTC, Shard.IN_TRANSIT_AAPL}) {
            BigDecimal pipe = BigDecimal.ZERO;
            for (Shard s : Shards.all()) pipe = pipe.add(s.balance(clearing));
            assertEquals(0, BigDecimal.ZERO.compareTo(pipe),
                    "clearing account " + clearing + " must settle to zero across the fleet");
        }
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 5: moving house creates and destroys nothing · the fleet's books are unchanged")
    void lesson5_conservation() throws Exception {
        BigDecimal eurBefore = fleetTotal("EUR"), btcBefore = fleetTotal("BTC"), aaplBefore = fleetTotal("AAPL");

        Relocation.relocate(IGOR, UK);

        assertEquals(0, eurBefore.compareTo(fleetTotal("EUR")), "no euro created or lost");
        assertEquals(0, btcBefore.compareTo(fleetTotal("BTC")), "no bitcoin created or lost");
        assertEquals(0, aaplBefore.compareTo(fleetTotal("AAPL")), "no share created or lost");
        assertEquals(0, BigDecimal.ZERO.compareTo(Shards.inFlight()), "nothing left in the pipe");
        assertEquals(0, drift(Shards.s(EU)).size(), "no cache drift in eu");
        assertEquals(0, drift(Shards.s(UK)).size(), "no cache drift in uk");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 6: repair · a shelf stranded by the old build comes home, and the second run is a no-op")
    void lesson6_repairBringsAStrandedShelfHome() throws Exception {
        Shard eu = Shards.s(EU), uk = Shards.s(UK);
        BigDecimal savings = eu.balance(IGOR + Products.SAVINGS);
        BigDecimal card = eu.balance(IGOR + Products.CARD);
        BigDecimal btc = eu.balance(IGOR + Products.BTC);
        assertTrue(savings.signum() > 0, "there is real money to strand");

        // reproduce EXACTLY what the old relocation did: move the main
        // account and flip the customer pointer, shelf left behind
        Directory.setMoving(IGOR, true);
        BigDecimal main = eu.balance(IGOR);
        UUID tx = UUID.randomUUID();
        eu.depart(tx, IGOR, IGOR, main);
        uk.createCustomer(IGOR, "igor");
        uk.arrive(tx, IGOR, main);
        Directory.flip(IGOR, UK);

        // the bug, reproduced: the customer is in uk, the shelf is in eu
        assertEquals(UK, Shards.forCustomer(IGOR).index);
        assertFalse(uk.hasAccount(IGOR + Products.SAVINGS), "the stranded state");
        assertEquals(0, savings.compareTo(eu.balance(IGOR + Products.SAVINGS)), "money on the wrong soil");

        assertTrue(Relocation.repairShelves() > 0, "the repair finds work to do");

        assertEquals(0, savings.compareTo(uk.balance(IGOR + Products.SAVINGS)), "savings came home");
        assertEquals(0, card.compareTo(uk.balance(IGOR + Products.CARD)), "the card debt came home");
        assertEquals(0, btc.compareTo(uk.balance(IGOR + Products.BTC)), "the bitcoin came home");
        assertEquals(0, BigDecimal.ZERO.compareTo(eu.balance(IGOR + Products.SAVINGS)), "and left eu empty");
        assertEquals(UK, Shards.forCustomer(IGOR + Products.SAVINGS).index, "routing came home too");
        assertEquals(0, sumZero(eu).size());
        assertEquals(0, sumZero(uk).size());

        // idempotent: nothing is stranded any more, so there is nothing to move
        assertEquals(0, Relocation.repairShelves(), "the second run is a no-op");
        assertEquals(0, savings.compareTo(uk.balance(IGOR + Products.SAVINGS)), "and it moved nothing twice");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 7: an absent account is an ERROR · reading it as €0.00 is how money goes missing quietly")
    void lesson7_absentIsNotZero() throws Exception {
        try (Connection c = Shards.s(UK).open()) {
            assertThrows(IllegalArgumentException.class,
                    () -> Ledger.cachedBalanceOn(c, IGOR + Products.SAVINGS),
                    "igor's savings do not exist in uk · say so, do not answer zero");
        }
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

    private static List<Long> drift(Shard s) throws Exception {
        try (Connection c = s.open()) {
            return Ledger.driftedAccountsOn(c);
        }
    }

    /** every account of one currency, across the whole fleet, summed */
    private static BigDecimal fleetTotal(String currency) throws Exception {
        BigDecimal sum = BigDecimal.ZERO;
        for (Shard s : Shards.all()) {
            try (Connection c = s.open();
                 var ps = c.prepareStatement("SELECT COALESCE(SUM(balance), 0) FROM accounts WHERE currency = ?")) {
                ps.setString(1, currency);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    sum = sum.add(rs.getBigDecimal(1));
                }
            }
        }
        return sum;
    }
}
