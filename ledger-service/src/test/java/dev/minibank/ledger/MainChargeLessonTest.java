package dev.minibank.ledger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE MAIN-ACCOUNT CHARGE · the shop's second rail.
 *
 * A card charge lives on the card; a main-account charge must live where the
 * customer actually looks — the EUR statement, as a named debit. One call,
 * journaled by the caller's reference, idempotent on retry: the same
 * reference is the same money, asked again.
 */
class MainChargeLessonTest {

    static final Instant T0 = Instant.parse("2027-07-01T00:00:00Z");
    static final AtomicLong NEXT = new AtomicLong(7500);

    @BeforeAll
    static void boot() throws Exception {
        Shards.boot(
                System.getenv().getOrDefault("MINIBANK_SHARD0_URL", "jdbc:postgresql://localhost:5434/minibank"),
                System.getenv().getOrDefault("MINIBANK_SHARD1_URL", "jdbc:postgresql://localhost:5435/minibank"),
                "minibank", "minibank", 5);
        for (Shard s : Shards.all()) s.createSchema();
        Directory.createOwnDatabase();
    }

    @BeforeEach
    void freshBooks() throws Exception {
        Fixtures.resetShards();
    }

    private static long customer() throws Exception {
        long id = NEXT.incrementAndGet();
        Shards.forCustomer(id).createCustomer(id, "payer-" + id);
        Products.ensureFor(id);
        Directory.register(id, "payer-" + id, (int) (id % 2));
        return id;
    }

    private static void fund(long id, String eur) throws Exception {
        Shards.forCustomer(id).transferLocal(UUID.randomUUID(), Shard.WORLD, id, new BigDecimal(eur));
    }

    @Test
    void aMainChargeDebitsTheStatementAndNamesTheMerchant() throws Exception {
        long me = customer();
        fund(me, "200.00");
        UUID ref = UUID.randomUUID();

        Ledger.TransferResult r = Products.chargeMain(ref, me, new BigDecimal("79.00"), "minimart");

        assertInstanceOf(Ledger.Ok.class, r, "the charge lands");
        assertEquals(0, new BigDecimal("121.00").compareTo(Products.balance(me, 0)),
                "the main account is lighter by exactly the purchase");
        // and the merchant account at the bank holds it
        assertEquals(0, new BigDecimal("79.00").compareTo(
                Shards.forCustomer(me).balance(Shard.CAFE)));
    }

    @Test
    void theSameReferenceChargedTwiceIsOneDebit() throws Exception {
        long me = customer();
        fund(me, "200.00");
        UUID ref = UUID.randomUUID();

        Products.chargeMain(ref, me, new BigDecimal("79.00"), "minimart");
        Ledger.TransferResult replay = Products.chargeMain(ref, me, new BigDecimal("79.00"), "minimart");

        assertInstanceOf(Ledger.AlreadyProcessed.class, replay,
                "a retried checkout re-asks the same money and gets the same answer");
        assertEquals(0, new BigDecimal("121.00").compareTo(Products.balance(me, 0)),
                "one debit, no matter how many times the network asks");
    }

    @Test
    void aChargeTheAccountCannotCoverIsRefusedNotOverdrawn() throws Exception {
        long me = customer();
        fund(me, "50.00");

        Ledger.TransferResult r = Products.chargeMain(UUID.randomUUID(), me, new BigDecimal("79.00"), "minimart");

        assertInstanceOf(Ledger.InsufficientFunds.class, r);
        assertEquals(0, new BigDecimal("50.00").compareTo(Products.balance(me, 0)),
                "a decline moves nothing");
    }
}
