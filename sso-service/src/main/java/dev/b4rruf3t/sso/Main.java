package dev.b4rruf3t.sso;

import java.io.IOException;

/** Bootstrap: wire everything together and start the server. */
public final class Main {
    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("SSO_PORT", "8090"));
        String issuer = System.getenv().getOrDefault("SSO_ISSUER", "https://auth.b4rruf3t.com");

        // SSO_DB_URL=memory boots against the in-memory directory — for local
        // demos and the end-to-end smoke test, where Postgres isn't the point.
        String dbUrl = System.getenv().getOrDefault("SSO_DB_URL",
            "jdbc:postgresql://localhost:5432/sso");

        ConnectionSource db;
        if ("memory".equals(dbUrl)) {
            db = new MemoryDb();
            System.out.println("SSO using in-memory directory (no Postgres)");
        } else {
            String dbUser = System.getenv().getOrDefault("SSO_DB_USER", "sso");
            String dbPass = System.getenv().getOrDefault("SSO_DB_PASS", "sso");
            db = new Db(dbUrl, dbUser, dbPass);
        }

        UserDirectory users = new UserDirectory(db);
        SessionStore sessions = new SessionStore(db);
        KeyManager keys = new KeyManager();
        TokenIssuer tokens = new TokenIssuer(keys, issuer);

        SsoServer server = new SsoServer(users, sessions, tokens, keys, issuer);
        server.start(port);
    }
}
