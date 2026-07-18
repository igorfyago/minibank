package dev.minibank.ledger;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * STAGE 6 · MOVING A CUSTOMER BETWEEN REGIONS.
 *
 * This is the question that corners most people who design a sharded system:
 * "fine, you sharded · now how do you ever MOVE anything?" The answer the
 * bank already contains: a balance is money, and money moves BY TRANSFER.
 *
 *   1. create the customer's WHOLE SHELF in the destination region (empty):
 *      the main account and every product account. A customer is not one
 *      account · leaving savings, card, loan and assets behind would strand
 *      real money on foreign soil, which is precisely what residency
 *      forbids. Moving "the customer" means moving all of them.
 *   2. set MOVING in the directory · the router pauses new transfers for
 *      this customer (a write-pause of milliseconds; reads keep working)
 *   3. transfer EVERY balance through the standard cross-shard saga ·
 *      depart from the old home, arrive at the new one. Not special code:
 *      THE saga, generalized over sign (a card debt travels the other way)
 *      and currency (BTC rides the BTC clearing account, so the per-currency
 *      sum-zero audit passes on both sides at every instant).
 *   4. flip the directory pointer for the products AND the customer: the
 *      shelf's routing rows are entries in the same table, and a half-
 *      flipped directory is a customer whose savings live abroad.
 *   5. failure? unwind: every leg that departed but never arrived bounces
 *      back to the source, then the write-pause lifts and the customer
 *      carries on at the old home. Each step is idempotent (account
 *      creation is guarded, every saga leg is gated by txId, the flip is an
 *      UPDATE), so nothing here can double money.
 *
 * The relocation calls arrive() DIRECTLY for promptness · and the same
 * departed event still rides Kafka to the applier, which will find the
 * arrival gate already claimed and shrug. The duplicate is not a bug we
 * tolerate; it is the design defending itself.
 *
 * History deliberately stays on the old region as an archive (the account
 * remains, emptied). Statements are a read model; the MONEY is what must
 * live in the new region, and after the flip it does.
 */
public final class Relocation {

    private Relocation() {}

    public static void relocate(long customerId, int toShardIndex) throws Exception {
        Shard from = Shards.forCustomer(customerId);   // throws if already mid-move
        Shard to = Shards.s(toShardIndex);
        if (from == to) return;                        // already home

        String owner = Directory.owner(customerId);
        if (!to.hasAccount(customerId)) to.createCustomer(customerId, owner);
        Products.ensureOn(to, customerId);             // the shelf, waiting and empty

        Directory.setMoving(customerId, true);         // the write-pause begins
        UUID move = UUID.randomUUID();
        List<Long> crossed = new ArrayList<>();        // shelf legs already across
        try {
            // 1 · THE SHELF FIRST. Products travel by the generalized mover:
            //     any sign (a card debt goes the other way), any currency
            //     (BTC rides the BTC clearing account). These legs are
            //     unwindable, so a failure here leaves the customer exactly
            //     where they started.
            for (long off : Products.OFFSETS) {
                long acct = customerId + off;
                // a customer from before the product shelf existed has
                // nothing of this kind to move · absent is not an error here,
                // it is an empty leg
                if (!from.hasAccount(acct)) continue;
                UUID legId = leg(move, acct);
                BigDecimal amount = from.departBalance(legId, acct);
                if (amount.signum() != 0) crossed.add(acct);
                to.arriveBalance(legId, acct, amount);
            }
            // 2 · THE MAIN ACCOUNT LAST, through THE saga · unchanged, so the
            //     relocation still emits the departed event the map animates
            //     and the applier still meets a claimed arrival gate.
            BigDecimal balance = from.balance(customerId);
            if (balance.signum() > 0) {
                UUID txId = UUID.randomUUID();
                from.depart(txId, customerId, customerId, balance);   // old home: igor -> in_transit
                to.arrive(txId, customerId, balance);                 // new home: in_transit -> igor
                // Kafka will deliver the departed event to the applier too;
                // the arrival gate answers AlreadyProcessed. By design.
            }
            // 3 · the shelf's ROUTING rows move with the shelf. A customer in
            //     uk whose savings still route to eu is the bug this fixes:
            //     the router would send product writes to foreign soil.
            for (long off : Products.OFFSETS) Directory.flip(customerId + off, toShardIndex);
            Directory.flip(customerId, toShardIndex);  // the pointer flip · the move IS this line
        } catch (Exception e) {
            // unwind: every shelf leg that crossed goes back where it came
            // from, so a failed move is a move that never happened. Same
            // bounce the cross-region saga uses for a missing destination.
            for (long acct : crossed) {
                try {
                    UUID back = bounce(leg(move, acct));
                    // the return leg carries what the destination ACTUALLY
                    // holds, not what we remember sending · the two legs are
                    // then equal by construction, whatever happened between
                    from.arriveBalance(back, acct, to.departBalance(back, acct));
                } catch (Exception ignored) {
                    // best effort · the drift audit surfaces anything stuck
                }
            }
            Directory.setMoving(customerId, false);    // resume at the old home; retry later
            throw e;
        }
    }

    /**
     * REPAIR · bring home any shelf a previous build left behind.
     *
     * Relocation used to move only the main account, so customers who moved
     * region have product accounts (and product ROUTING rows) sitting on
     * their old shard: real money, on the wrong soil, invisible to the app
     * because the portfolio reads the home shard and finds nothing there.
     *
     * This is a reconciliation, not a migration: it reads what IS and moves
     * what is misplaced, using the same saga legs as a normal relocation.
     * Idempotent and safe to run on every boot · when nothing is stranded
     * it does nothing at all. Returns the number of accounts brought home.
     */
    public static int repairShelves() throws Exception {
        int moved = 0;
        for (long customerId : Directory.customerIds()) {
            Shard home;
            try {
                home = Shards.forCustomer(customerId);
            } catch (RuntimeException e) {
                continue;                       // mid-move or unrouted · leave it alone
            }
            Products.ensureOn(home, customerId);
            for (Shard other : Shards.all()) {
                if (other == home) continue;
                for (long off : Products.OFFSETS) {
                    long acct = customerId + off;
                    if (!other.hasAccount(acct)) continue;
                    UUID legId = UUID.nameUUIDFromBytes(
                            ("repair:" + acct + ":" + home.index).getBytes(StandardCharsets.UTF_8));
                    BigDecimal amount = other.departBalance(legId, acct);
                    // count the ARRIVAL, not the attempt · a replayed leg
                    // reports the amount it moved last time, and counting
                    // that would claim work on every boot forever
                    if (home.arriveBalance(legId, acct, amount)) {
                        moved++;
                        System.out.println("repaired: account " + acct + " (" + amount.toPlainString()
                                + ") " + other.name + " -> " + home.name);
                    }
                    // the routing row comes home too, balance or not
                    Directory.flip(acct, home.index);
                }
            }
        }
        return moved;
    }

    private static UUID leg(UUID move, long accountId) {
        return UUID.nameUUIDFromBytes((move + ":" + accountId).getBytes(StandardCharsets.UTF_8));
    }

    private static UUID bounce(UUID legId) {
        return UUID.nameUUIDFromBytes(("bounce:" + legId).getBytes(StandardCharsets.UTF_8));
    }
}
