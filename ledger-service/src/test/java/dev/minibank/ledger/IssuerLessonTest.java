package dev.minibank.ledger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THIS BANK AS A CARD ISSUER.
 *
 * A merchant somewhere else takes an order, its processor asks whether the
 * money is good, and this bank approves or declines against a limit it owns.
 * minimart is the merchant, minipay is the acquirer, minibank is the issuer,
 * and a customer here is the cardholder. That is the real four-party model, and
 * the point of these lessons is that becoming one corner of it required almost
 * no new machinery: the card already had authorize, capture and release, and
 * already enforced its limit with a CHECK constraint rather than an
 * if-statement.
 *
 * What is genuinely new is the boundary. An acquirer is a different company. It
 * may learn whether a charge is good, and it may not learn who the person is,
 * what they have, or what is left.
 */
class IssuerLessonTest {

    static final Instant T0 = Instant.parse("2027-05-01T00:00:00Z");
    static final java.util.concurrent.atomic.AtomicLong NEXT = new java.util.concurrent.atomic.AtomicLong(7100);

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

    /** A cardholder with the standard credit limit of 1000 and no history. */
    private static long cardholder() throws Exception {
        long id = NEXT.incrementAndGet();
        Shards.forCustomer(id).createCustomer(id, "holder-" + id);
        Products.ensureFor(id);
        Directory.register(id, "holder-" + id, (int) (id % 2));
        return id;
    }

    /**
     * LESSON 1 · THE ACQUIRER LEARNS WHETHER, NOT WHO OR HOW MUCH.
     *
     * The whole reason a token exists. A customer id would tell the processor
     * who the person is and which region they bank in, and would be the join
     * key that eventually tempts somebody to read the bank's database directly.
     *
     * The second half is the more important restraint: there is NO endpoint
     * that tells an acquirer how much credit is left. If there were, a processor
     * would check before authorising, and a check before a decision is a race by
     * construction, because the answer is stale the moment it is given.
     */
    @Test
    void lesson1_the_token_carries_no_information_about_the_cardholder() throws Exception {
        long customer = cardholder();
        Issuer.Instrument card = Issuer.issueCard(customer);

        assertTrue(card.token().startsWith("mbc_"), "an opaque token: " + card.token());
        assertFalse(card.token().contains(String.valueOf(customer)),
                "the customer id is NOT recoverable from the token");
        assertEquals("active", card.status());

        // what a receipt may say, and nothing else
        Issuer.Instrument seen = Issuer.describe(card.token());
        assertEquals(card.last4(), seen.last4());
        assertEquals("minibank credit", seen.brandLabel());

        // issuing twice returns the same card rather than a second one
        assertEquals(card.token(), Issuer.issueCard(customer).token(),
                "a customer pressing the button twice ends up with one card");
        System.out.println("lesson 1: the acquirer sees " + seen.brandLabel() + " ending " + seen.last4()
                + " and nothing else about the cardholder");
    }

    /**
     * LESSON 2 · THE LIMIT IS A CONSTRAINT, NOT A CHECK IN THE ISSUER.
     *
     * Nothing in the issuer reads a balance and compares it. The decline comes
     * out of the ledger's own CHECK constraint under a row lock, which is why
     * this next assertion can be made at all: twenty processors authorising the
     * same card at the same instant cannot collectively exceed the limit.
     *
     * An issuer that decided by reading first would pass every sequential test
     * and overshoot the moment two terminals were used at once, which is
     * precisely the situation a card is for.
     */
    @Test
    void lesson2_concurrent_authorisations_cannot_exceed_the_limit() throws Exception {
        long shopper = cardholder();
        Issuer.Instrument card = Issuer.issueCard(shopper);

        // the card's limit is 1000. Twenty processors each ask for 100.
        AtomicInteger approved = new AtomicInteger(), declined = new AtomicInteger();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                fs.add(pool.submit(() -> {
                    Issuer.Decision d = Issuer.authorize(UUID.randomUUID(), card.token(),
                            new BigDecimal("100.00"), "EUR", T0);
                    (d.approved() ? approved : declined).incrementAndGet();
                    return null;
                }));
            }
            for (Future<?> f : fs) f.get();
        }

        assertEquals(10, approved.get(), "exactly the limit's worth, decided under the lock");
        assertEquals(10, declined.get(), "and the rest declined, not overdrawn");
        System.out.println("lesson 2: 20 concurrent authorisations of 100 against a 1000 limit -> "
                + approved.get() + " approved, " + declined.get() + " declined");
    }

    /**
     * LESSON 3 · A RETRIED AUTHORISATION IS THE SAME AUTHORISATION.
     *
     * Card networks retry. A bank that places a second hold because the network
     * was uncertain has taken a customer's credit twice for one purchase, and
     * the customer discovers it at the next till.
     *
     * The id is minted by the ACQUIRER, which is the only party that knows
     * whether this is a new purchase or a repeat of one.
     */
    @Test
    void lesson3_a_retried_authorisation_holds_the_money_once() throws Exception {
        long shopper = cardholder();
        Issuer.Instrument card = Issuer.issueCard(shopper);
        UUID authId = UUID.randomUUID();

        Issuer.Decision first = Issuer.authorize(authId, card.token(), new BigDecimal("250.00"), "EUR", T0);
        assertTrue(first.approved());

        for (int i = 0; i < 5; i++) {
            Issuer.Decision again = Issuer.authorize(authId, card.token(), new BigDecimal("250.00"), "EUR", T0);
            assertTrue(again.approved(), "the same answer every time");
            assertEquals(first.authorizationId(), again.authorizationId());
        }

        // 250 held once, so 750 of the 1000 limit is still usable
        assertTrue(Issuer.authorize(UUID.randomUUID(), card.token(), new BigDecimal("750.00"), "EUR", T0).approved(),
                "only ONE hold was placed, so the rest of the limit is still there");
        assertFalse(Issuer.authorize(UUID.randomUUID(), card.token(), new BigDecimal("1.00"), "EUR", T0).approved(),
                "and now it is genuinely used up");
        System.out.println("lesson 3: one authorisation retried 6 times held 250 once, leaving 750 usable");
    }

    /**
     * LESSON 4 · A VOIDED HOLD GIVES THE LIMIT BACK, EXACTLY.
     *
     * The order fell through. A customer must not have their credit consumed by
     * a purchase that never happened, and a release that returns approximately
     * the right amount is a slow leak that nobody notices until a card declines
     * for no visible reason.
     */
    @Test
    void lesson4_a_void_returns_the_customer_their_credit() throws Exception {
        long shopper = cardholder();
        Issuer.Instrument card = Issuer.issueCard(shopper);

        UUID authId = UUID.randomUUID();
        assertTrue(Issuer.authorize(authId, card.token(), new BigDecimal("1000.00"), "EUR", T0).approved(),
                "the whole limit is held");
        assertFalse(Issuer.authorize(UUID.randomUUID(), card.token(), new BigDecimal("1.00"), "EUR", T0).approved(),
                "so nothing else fits");

        assertTrue(Issuer.voidAuthorization(authId, T0), "the merchant gives up");
        assertTrue(Issuer.voidAuthorization(authId, T0), "and giving up twice is harmless");

        assertTrue(Issuer.authorize(UUID.randomUUID(), card.token(), new BigDecimal("1000.00"), "EUR", T0).approved(),
                "THE WHOLE LIMIT CAME BACK, not most of it");
        System.out.println("lesson 4: a voided hold returned the full 1000, and voiding twice changed nothing");
    }

    /**
     * LESSON 5 · CAPTURED MONEY IS GONE, AND A LATE VOID CANNOT UNDO IT.
     *
     * The lifecycle has an order, and the interesting case is the one that
     * arrives out of order: a void that turns up after the money was already
     * taken. Answering "yes, voided" to that would tell the acquirer the
     * customer had been refunded when they had not.
     */
    @Test
    void lesson5_a_void_after_capture_is_refused_rather_than_pretended() throws Exception {
        long shopper = cardholder();
        Issuer.Instrument card = Issuer.issueCard(shopper);
        UUID authId = UUID.randomUUID();

        assertTrue(Issuer.authorize(authId, card.token(), new BigDecimal("400.00"), "EUR", T0).approved());
        assertEquals(1, Issuer.outstandingHolds() > 0 ? 1 : 0, "a hold is outstanding while it is unresolved");

        assertTrue(Issuer.capture(authId, T0), "the merchant ships and takes the money");
        assertTrue(Issuer.capture(authId, T0), "capturing twice takes it once");

        assertFalse(Issuer.voidAuthorization(authId, T0),
                "A VOID AFTER CAPTURE IS REFUSED: saying yes would report a refund that never happened");
        System.out.println("lesson 5: capture is idempotent, and a late void is refused rather than pretended");
    }

    /**
     * LESSON 6 · A DECLINE IS AN ANSWER, NOT AN ERROR.
     *
     * An unusable instrument, an absurd amount, an unknown token: all of these
     * are the bank correctly answering the question it was asked. The acquirer
     * gets a decline with a reason, and nothing about them is an exception,
     * because an acquirer that saw a fault would retry, and retrying a decline
     * is how a customer gets declined five times instead of once.
     */
    @Test
    void lesson6_an_unusable_card_declines_cleanly_with_a_reason() throws Exception {
        Issuer.Decision unknown = Issuer.authorize(UUID.randomUUID(), "mbc_does_not_exist",
                new BigDecimal("10.00"), "EUR", T0);
        assertFalse(unknown.approved());
        assertEquals("instrument not usable", unknown.reason(), "and it says why, without saying whose");

        long shopper = cardholder();
        Issuer.Instrument card = Issuer.issueCard(shopper);
        Issuer.Decision negative = Issuer.authorize(UUID.randomUUID(), card.token(),
                new BigDecimal("-50.00"), "EUR", T0);
        assertFalse(negative.approved(), "a merchant cannot authorise a negative amount into a refund");
        assertEquals("amount must be positive", negative.reason());

        Issuer.Decision tooMuch = Issuer.authorize(UUID.randomUUID(), card.token(),
                new BigDecimal("5000.00"), "EUR", T0);
        assertFalse(tooMuch.approved());
        assertEquals("insufficient credit", tooMuch.reason(),
                "the one decline reason a merchant can actually act on");
        System.out.println("lesson 6: unknown card, negative amount and over-limit all decline with a reason");
    }

    /**
     * LESSON 7 · THE ATTEMPT AGAINST A CARD WE NEVER ISSUED IS RECORDED.
     *
     * This one exists because writing the FIRST version of the schema got it
     * wrong in an instructive way. A foreign key from the authorisation to the
     * instrument reads as obviously correct and silently discards the rows an
     * issuer most wants: attempts against tokens that do not exist. A run of
     * those is what card testing looks like from the inside, and it is visible
     * only in the authorisation log, so a table that refuses to store them makes
     * the bank blind to the one pattern it should notice first.
     *
     * The authorisation record is a log of what was ASKED, not only of what had
     * a valid answer.
     */
    @Test
    void lesson7_an_authorisation_against_an_unknown_card_is_still_written_down() throws Exception {
        long before = countUnknownDeclines();

        // three attempts against cards this bank has never issued, as somebody
        // working through numbers would produce
        for (int i = 0; i < 3; i++) {
            Issuer.Decision d = Issuer.authorize(UUID.randomUUID(), "mbc_fabricated_" + i,
                    new BigDecimal("1.00"), "EUR", T0);
            assertFalse(d.approved(), "an invented card is declined");
        }

        assertEquals(before + 3, countUnknownDeclines(),
                "and EVERY attempt was recorded, because the log is what makes the pattern visible");
        System.out.println("lesson 7: 3 attempts on cards we never issued were all written to the authorisation log");
    }

    private static long countUnknownDeclines() throws Exception {
        try (var c = Directory.openForRead();
             var ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM card_authorizations WHERE state = 'declined' AND customer_id = 0");
             var rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
