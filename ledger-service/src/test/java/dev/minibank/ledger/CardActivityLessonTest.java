package dev.minibank.ledger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE CARDHOLDER'S OWN VIEW OF THEIR CARD.
 *
 * The issuer side of the card was built for the acquirer: it answers whether
 * the money is good and deliberately little else. But a charge the cardholder
 * cannot SEE is a charge that did not happen as far as they are concerned —
 * the 2026-07-23 mart purchase landed in card_authorizations and was invisible
 * everywhere the customer actually looks.
 *
 * These lessons pin the cardholder surface: every authorization names its
 * merchant, and the bank answers "what happened on my card" newest-first with
 * the merchant, the amount, the state and the card's last four.
 */
class CardActivityLessonTest {

    static final Instant T0 = Instant.parse("2027-06-01T00:00:00Z");
    static final AtomicLong NEXT = new AtomicLong(7300);

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

    private static long cardholder() throws Exception {
        long id = NEXT.incrementAndGet();
        Shards.forCustomer(id).createCustomer(id, "viewer-" + id);
        Products.ensureFor(id);
        Directory.register(id, "viewer-" + id, (int) (id % 2));
        return id;
    }

    /** This suite's merchant tag: the directory outlives a shard reset, so
     *  each run names its charges uniquely and reads only its own back. */
    private static final String SHOP = "minimart-" + UUID.randomUUID().toString().substring(0, 8);

    private static Issuer.Instrument chargedCard(long customer, String merchant,
                                                 String amount, Instant at) throws Exception {
        Issuer.Instrument card = Issuer.issueCard(customer);
        UUID ref = UUID.randomUUID();
        Issuer.Decision d = Issuer.authorize(ref, card.token(), new BigDecimal(amount), "EUR", merchant, at);
        assertTrue(d.approved(), "test setup: the charge is approved");
        assertTrue(Issuer.capture(ref, at.plusSeconds(60)));
        return card;
    }

    @Test
    void anAuthorizationRemembersWhoseShopTookTheMoney() throws Exception {
        long me = cardholder();
        chargedCard(me, SHOP, "79.00", T0);

        List<Issuer.Activity> mine = Issuer.activity(me, 10).stream()
                .filter(a -> a.merchant().equals(SHOP)).toList();

        assertEquals(1, mine.size());
        assertEquals(SHOP, mine.get(0).merchant(),
                "the charge names the shop that took it, not a mystery acquirer");
    }

    @Test
    void theActivityAnswersNewestFirstWithWhatAReceiptNeeds() throws Exception {
        long me = cardholder();
        Issuer.Instrument card = chargedCard(me, SHOP, "29.00", T0);
        chargedCard(me, SHOP, "89.00", T0.plusSeconds(3600));

        List<Issuer.Activity> mine = Issuer.activity(me, 10).stream()
                .filter(a -> a.merchant().equals(SHOP)).toList();

        assertEquals(2, mine.size());
        assertEquals(0, new BigDecimal("89.00").compareTo(mine.get(0).amount()),
                "newest first: the later, larger charge leads");
        assertEquals("captured", mine.get(0).state());
        assertEquals(card.last4(), mine.get(0).last4(),
                "the receipt says which card, the way a real statement does");
    }

    @Test
    void aChargeWithoutANamedMerchantIsStillHonest() throws Exception {
        long me = cardholder();
        Issuer.Instrument card = Issuer.issueCard(me);
        UUID ref = UUID.randomUUID();
        // the acquirer-shaped call: no merchant named
        Issuer.authorize(ref, card.token(), new BigDecimal("12.00"), "EUR", T0);

        List<Issuer.Activity> mine = Issuer.activity(me, 10).stream()
                .filter(a -> a.id().equals(ref)).toList();

        assertEquals(1, mine.size());
        assertNotNull(mine.get(0).merchant(), "never null on the customer's own screen");
        assertEquals("approved", mine.get(0).state(),
                "an open hold shows as what it is: approved, not captured");
    }

    @Test
    void theLimitIsRespectedAndStrangersSeeNothing() throws Exception {
        long me = cardholder();
        long stranger = cardholder();
        for (int i = 0; i < 3; i++) chargedCard(me, SHOP, "10.00", T0.plusSeconds(i * 60));

        assertEquals(2, Issuer.activity(me, 2).size(), "the page size is honoured");
        assertTrue(Issuer.activity(stranger, 10).isEmpty(),
                "another customer's card life is not your business");
    }
}
