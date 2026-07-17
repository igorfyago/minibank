package dev.minibank.ledger;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * STAGE 6 · MOVING A CUSTOMER BETWEEN REGIONS.
 *
 * This is the question that corners people in system-design interviews:
 * "fine, you sharded · now how do you ever MOVE anything?" The answer the
 * bank already contains: a balance is money, and money moves BY TRANSFER.
 *
 *   1. create the customer's account in the destination region (empty)
 *   2. set MOVING in the directory · the router pauses new transfers for
 *      this customer (a write-pause of milliseconds; reads keep working)
 *   3. transfer the WHOLE balance through the standard cross-shard saga ·
 *      depart from the old home, arrive at the new one. Not special code:
 *      THE saga, reused. The books balance on both sides at every instant.
 *   4. flip the directory pointer: new region, moving=false
 *   5. (crash anywhere? unset moving and retry · every step is idempotent:
 *      account creation is guarded, the saga is gated by txId, the flip is
 *      an UPDATE. Nothing here can double money.)
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

        Directory.setMoving(customerId, true);         // the write-pause begins
        try {
            BigDecimal balance = from.balance(customerId);
            if (balance.signum() > 0) {
                UUID txId = UUID.randomUUID();
                from.depart(txId, customerId, customerId, balance);   // old home: igor -> in_transit
                to.arrive(txId, customerId, balance);                 // new home: in_transit -> igor
                // Kafka will deliver the departed event to the applier too;
                // the arrival gate answers AlreadyProcessed. By design.
            }
            Directory.flip(customerId, toShardIndex);  // the pointer flip · the move IS this line
        } catch (Exception e) {
            Directory.setMoving(customerId, false);    // resume at the old home; retry later
            throw e;
        }
    }
}
