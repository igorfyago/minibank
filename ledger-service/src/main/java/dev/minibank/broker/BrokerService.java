package dev.minibank.broker;

import dev.minibank.ledger.BankAuth;
import dev.minibank.ledger.OutboxRelay;
import dev.minibank.ledger.Shards;
import dev.minibank.ledger.SsoIdentity;

import java.util.Optional;

/**
 * THE BROKER, AS ITS OWN PROCESS.
 *
 * Same image as the bank, different entrypoint · exactly what the FX service
 * already does. That is a deliberate middle position, and the argument is
 * worth being able to make out loud:
 *
 *   SEPARATE where separation is load-bearing · its own process, so it can
 *   be scaled, deployed and crashed on its own; its own database, so the
 *   boundary is enforced by not having a connection rather than by a code
 *   review.
 *
 *   SHARED where separation costs and buys nothing · one build, one image,
 *   one dependency set. Splitting the repository would add a release
 *   pipeline and cross-repo version skew, and would buy independence this
 *   team of one does not need. Split a service when a boundary earns it;
 *   split a repository when a TEAM earns it.
 *
 * Note what it reuses and what it does not. It reuses OutboxRelay verbatim,
 * because the outbox table shape is a CONVENTION in this bank and the relay
 * only ever cared about the shape, not about whose rows they are. It does
 * NOT reach into the ledger's database, which is the boundary that matters.
 */
public final class BrokerService {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("BROKER_PORT", "8091"));
        // MINIBANK_KAFKA, not a name of its own · every service in this bank
        // reads the same variable, and a second spelling for the same address
        // is a configuration bug waiting for a deployment to find it
        String kafka = System.getenv().getOrDefault("MINIBANK_KAFKA", "localhost:9092");

        BrokerDb.createOwnDatabase();
        Catalog.seed();

        // THE AUDITOR'S SECOND BOOK, and the repair that needs it.
        //
        // Reconciliation compares this service's positions against the
        // ledger's custody of the same assets, so it needs to be able to read
        // the shards. Backfill reconstructs the fills for holdings the ledger
        // recorded before the trading path was unified.
        //
        // BEST EFFORT, DELIBERATELY. Both are read-mostly repairs that matter
        // to some customers; failing to start the broker matters to all of
        // them. This is the same argument Main already makes for the shelf
        // repair, and it applies here for the same reason: a reconciliation
        // that can refuse to let a service boot is worse than the drift it
        // measures.
        try {
            Shards.boot(
                    System.getenv().getOrDefault("MINIBANK_SHARD0_URL", "jdbc:postgresql://localhost:5434/minibank"),
                    System.getenv().getOrDefault("MINIBANK_SHARD1_URL", "jdbc:postgresql://localhost:5435/minibank"),
                    "minibank", "minibank", 2);
            Backfill.Report repair = Backfill.run();
            if (repair.changedAnything()) System.out.println(repair);
            for (String s : repair.skipped()) System.out.println("backfill skipped · " + s);
        } catch (Exception e) {
            System.err.println("reconciliation and backfill unavailable · " + e
                    + " · the broker is starting anyway; /api/audit will say so rather than report zero");
        }

        BrokerPort venue = venueFromEnv();
        Broker broker = new Broker(venue);

        // SSO counts here now. The broker shipped ANONYMOUS, so caller() always
        // fell to the ?customer= param · a read-anybody's-book IDOR the instant a
        // token can name someone. A BankAuth turns a valid token (audience
        // bank.b4rruf3t.com; the estate's shared directory maps its subject to a
        // customer) into that customer's id, and the two-arg BrokerApi makes the
        // token win over the parameter. Still permissive: no token, no identity,
        // and the four demo accounts behave exactly as before. Enforcement · the
        // day the param is refused on a private book · stays a separate decision.
        //
        // This is the adapter CallerIdentity's own javadoc sketched, now that
        // the shared validator is a resolvable artifact rather than in flight.
        SsoIdentity auth = new BankAuth(
                System.getenv().getOrDefault("SSO_ISSUER", BankAuth.DEFAULT_ISSUER));
        CallerIdentity who = header ->
                (auth.verdict(header) instanceof SsoIdentity.Verdict.Known k && k.customerId() != null)
                        ? Optional.of(k.customerId()) : Optional.empty();
        new BrokerApi(broker, who).start(port);

        // one relay, pointed at THIS service's outbox · the ledger will
        // settle the cash leg from the far side of the topic
        new OutboxRelay(kafka, BrokerDb::open).runLoop(500);
        // ... and one consumer for what the ledger decided about our fills
        SettlementConsumer.start(kafka, broker);

        System.out.println("broker on :" + port + " · venue=" + venue.name()
                + " · publishing to topic '" + Broker.TOPIC_ORDERS + "'");
        Thread.currentThread().join();
    }

    /**
     * Which venue is configured. Defaults to the simulation, and the default
     * is the point: routing real orders should require somebody to have
     * typed something deliberate, not merely to have deployed.
     */
    static BrokerPort venueFromEnv() {
        String want = System.getenv().getOrDefault("BROKER_VENUE", "simulated");
        return switch (want) {
            case "ibkr" -> new IbkrVenue();
            case "simulated" -> new SimulatedVenue();
            default -> throw new IllegalArgumentException("unknown BROKER_VENUE: " + want);
        };
    }
}
