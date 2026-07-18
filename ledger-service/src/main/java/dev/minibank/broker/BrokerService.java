package dev.minibank.broker;

import dev.minibank.ledger.OutboxRelay;

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

        BrokerPort venue = venueFromEnv();
        Broker broker = new Broker(venue);
        new BrokerApi(broker).start(port);

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
