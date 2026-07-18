package dev.minibank.ledger;

import org.flywaydb.core.Flyway;

/**
 * FLYWAY · the schema is versioned SQL, not code.
 *
 * Every database in the bank is migrated the same way: a set of Vn__*.sql
 * files under db/&lt;role&gt;, applied in order, each recorded in that database's
 * own flyway_schema_history (browsable live in SQL Studio).
 *
 * baselineOnMigrate is the safety net for an already-running database: the
 * live shards were created before Flyway existed, so Flyway baselines their
 * current state as version 0 and then applies V1+ · and because every
 * migration is written idempotently (CREATE ... IF NOT EXISTS), applying
 * them over an existing schema is a no-op. Data is never touched.
 *
 * Flyway is a library, not a framework · it owns the schema, not the app.
 */
public final class Migrate {

    private Migrate() {}

    public static void run(String jdbcUrl, String user, String password, String location) {
        Flyway.configure()
                .dataSource(jdbcUrl, user, password)
                .locations(location)
                .baselineOnMigrate(true)     // an existing schema is baselined, never wiped
                .baselineVersion("0")
                .table("flyway_schema_history")
                .load()
                .migrate();
        System.out.println("flyway: migrated " + location + " on " + jdbcUrl);
    }
}
