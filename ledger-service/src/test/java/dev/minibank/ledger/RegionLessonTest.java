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
 * STAGE 6 · REGIONS: SHARDS WITH A PASSPORT.
 *
 * Regions are not about load; they are about LAW. Residency is a fact
 * about the customer, so the router becomes a directory lookup instead of
 * arithmetic · and everything built in stage 5 (locality, sagas, pools,
 * in_transit) carries over without changing a line.
 *
 *   lesson 1  routing is a FACT now: the directory decides, not a formula
 *   lesson 2  cross-REGION payment = the stage-5 saga, verbatim
 *   lesson 3  relocation: the balance moves BY TRANSFER + a pointer flip
 *   lesson 4  the write-pause: mid-move transfers are refused, retriable
 *   lesson 5  the applier's duplicate settles into the arrival gate · by design
 *
 * Requires: docker compose up -d   (shards :5434/:5435, directory on :5433)
 */
class RegionLessonTest {

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
    static void unplug() {
        Shards.setResolver(null);   // stage-5 lessons keep their arithmetic router
    }

    @BeforeEach
    void freshWorld() throws Exception {
        Fixtures.resetShards();
        Fixtures.resetDirectory();

        Directory.register(IGOR, "igor", EU);
        Directory.register(COCO, "coco", UK);
        Shards.forCustomer(IGOR).createCustomer(IGOR, "igor");
        Shards.forCustomer(COCO).createCustomer(COCO, "coco");
        Shards.forCustomer(IGOR).transferLocal(UUID.randomUUID(), Shard.WORLD, IGOR, eur("500.00"));
        Shards.forCustomer(COCO).transferLocal(UUID.randomUUID(), Shard.WORLD, COCO, eur("500.00"));
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 1: residency is a FACT, not a formula · the directory routes, and unknowns are refused")
    void lesson1_theDirectoryDecides() throws Exception {
        assertEquals(EU, Shards.forCustomer(IGOR).index, "the directory says igor lives in eu");
        assertEquals(UK, Shards.forCustomer(COCO).index, "and coco in uk");
        assertEquals("eu", Shards.regionName(EU));
        assertEquals("uk", Shards.regionName(UK));

        // arithmetic would happily route customer 12 to shard 0. The
        // directory refuses: residency cannot be COMPUTED, only recorded.
        assertThrows(IllegalArgumentException.class, () -> Shards.forCustomer(12),
                "no residency on file, no routing · a hash function cannot know the law");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 2: a cross-REGION payment is the stage-5 saga, verbatim · zero new machinery")
    void lesson2_crossRegionIsTheSameSaga() throws Exception {
        UUID tx = UUID.randomUUID();
        assertTrue(Shards.plan(IGOR, COCO).crossShard(), "eu -> uk crosses regions");

        Shards.forCustomer(IGOR).depart(tx, IGOR, COCO, eur("30.00"));
        assertEquals(0, eur("30.00").compareTo(Shards.inFlight()), "in the pipe between regions");

        // the applier fed the outbox's own bytes, exactly as in stage 5.
        // Read the row whatever its published_at: whether a relay has already
        // forwarded it is not what this lesson is about.
        ShardApplier.handle(Fixtures.outboxEvent(Shards.s(EU), "departed:" + tx).payload());

        assertEquals(0, eur("530.00").compareTo(Shards.s(UK).balance(COCO)), "landed in uk");
        assertEquals(0, BigDecimal.ZERO.compareTo(Shards.inFlight()), "settled");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 3: relocation · the balance moves BY TRANSFER, then one pointer flips. That is resharding")
    void lesson3_relocationIsATransferPlusAPointerFlip() throws Exception {
        Relocation.relocate(IGOR, UK);

        assertEquals(UK, Shards.forCustomer(IGOR).index, "the directory now says uk");
        assertEquals(0, eur("500.00").compareTo(Shards.s(UK).balance(IGOR)), "every cent arrived");
        assertEquals(0, BigDecimal.ZERO.compareTo(Shards.s(EU).balance(IGOR)), "the old home is emptied");
        assertEquals(0, BigDecimal.ZERO.compareTo(Shards.inFlight()), "nothing left in the pipe");
        assertEquals(0, sumZero(Shards.s(EU)).size(), "eu books balance");
        assertEquals(0, sumZero(Shards.s(UK)).size(), "uk books balance");

        // history is deliberately archived on the old region: the emptied
        // account and all its entries remain · statements are a read
        // model; the MONEY is what had to move, and it did.
        assertTrue(Shards.s(EU).hasAccount(IGOR), "the archive remains in eu");

        // and life continues in the new region: igor and coco are now
        // NEIGHBOURS · the same payment that was a cross-region saga
        // yesterday is a plain local ACID transfer today.
        assertFalse(Shards.plan(IGOR, COCO).crossShard(), "same region now");
        assertInstanceOf(Ledger.Ok.class,
                Shards.forCustomer(IGOR).transferLocal(UUID.randomUUID(), IGOR, COCO, eur("25.00")));
        assertEquals(0, eur("475.00").compareTo(Shards.s(UK).balance(IGOR)));
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 4: the write-pause · mid-move the router refuses new transfers, and the refusal is an instruction: retry")
    void lesson4_theWritePause() throws Exception {
        Directory.setMoving(IGOR, true);    // freeze the mid-relocation moment

        assertThrows(Directory.CustomerMoving.class, () -> Shards.plan(IGOR, COCO),
                "no new transfers while the balance is travelling · milliseconds, not maintenance windows");

        Directory.setMoving(IGOR, false);   // the flip (or the rollback) ends the pause
        assertEquals(EU, Shards.forCustomer(IGOR).index, "and routing resumes instantly");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 5: the relocation's Kafka echo lands on a closed gate · the duplicate is the design defending itself")
    void lesson5_theDuplicateSettles() throws Exception {
        Relocation.relocate(IGOR, UK);      // arrive() was called directly

        // the departed event sits in eu's outbox; in production the relay
        // ships it and the applier processes it AGAIN. Simulate that:
        Outbox.Event echo = Fixtures.outboxEvent(Shards.s(EU), "departed:");
        ShardApplier.handle(echo.payload());
        ShardApplier.handle(echo.payload());

        assertEquals(0, eur("500.00").compareTo(Shards.s(UK).balance(IGOR)),
                "the arrival gate was already claimed · the echo changed nothing. Idempotency is not " +
                "a feature bolted on for retries; it is why bold moves like relocation stay simple.");
        assertEquals(0, BigDecimal.ZERO.compareTo(Shards.inFlight()));
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
