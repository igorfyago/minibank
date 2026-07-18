package dev.b4rruf3t.sso;

import java.io.IOException;

/** Bootstrap: wire everything together and start the server. */
public final class Main {
    public static void main(String[] args) throws IOException {
        String dbUrl = System.getenv().getOrDefault("SSO_DB_URL",
            "jdbc:postgresql://localhost:5432/sso");
        String dbUser = System.getenv().getOrDefault("SSO_DB_USER", "sso");
        String dbPass = System.getenv().getOrDefault("SSO_DB_PASS", "sso");
        int port = Integer.parseInt(System.getenv().getOrDefault("SSO_PORT", "8090"));
        String issuer = System.getenv().getOrDefault("SSO_ISSUER", "https://auth.b4rruf3t.com");

        Db db = new Db(dbUrl, dbUser, dbPass);
        UserDirectory users = new UserDirectory(db);
        SessionStore sessions = new SessionStore(db);
        KeyManager keys = new KeyManager();
        TokenIssuer tokens = new TokenIssuer(keys, issuer);

        SsoServer server = new SsoServer(users, sessions, tokens, keys, issuer);
        server.start(port);
    }
}
