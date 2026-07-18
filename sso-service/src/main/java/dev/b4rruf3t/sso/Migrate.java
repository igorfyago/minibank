package dev.b4rruf3t.sso;

import org.flywaydb.core.Flyway;

/** The schema is versioned SQL under db/migration, applied at boot — same
 *  doctrine as the ledger and the shop: the service owns its schema. */
public final class Migrate {

    private Migrate() {}

    /** Create the database if it is missing, then migrate. Lets the service
     *  be pointed at a bare Postgres server and bring itself up — the same
     *  bootstrap pattern the shop uses. SSO_ADMIN_URL points at any database
     *  on the server (typically the default); the real URL is derived from it. */
    public static void bootstrap(String adminUrl, String url, String user, String password) {
        if (adminUrl != null && !adminUrl.isBlank()) {
            String name = url.substring(url.lastIndexOf('/') + 1);
            try (java.sql.Connection c = java.sql.DriverManager.getConnection(adminUrl, user, password);
                 java.sql.Statement st = c.createStatement();
                 java.sql.ResultSet rs = st.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + name + "'")) {
                if (!rs.next()) {
                    try (java.sql.Statement create = c.createStatement()) {
                        create.execute("CREATE DATABASE " + name);
                    }
                }
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("cannot bootstrap the sso database", e);
            }
        }
        run(url, user, password);
    }

    public static void run(String url, String user, String password) {
        Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }
}
