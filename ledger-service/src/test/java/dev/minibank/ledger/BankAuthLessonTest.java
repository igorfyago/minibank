package dev.minibank.ledger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE BANK RECOGNISES A SIGNED-IN VISITOR · end to end, with real signatures.
 *
 * SsoIdentityLessonTest proves the bank's half of this with a stub: precedence,
 * the IDOR, the untouched demo. Its stub agrees with its own tokens by
 * construction, which is exactly the right simplification for those lessons and
 * exactly the wrong one for this one. A stub proves the wiring exists. It
 * cannot prove the wiring is wired to a VALIDATOR, and "the seam was connected
 * to something that always says yes" is a failure mode that passes every test
 * written against the seam.
 *
 * So every token below is a genuine RS256 JWT, signed with a real 2048-bit key,
 * put through the real dev.b4rruf3t.sso.client.SsoClient by way of the real
 * BankAuth, and resolved through the real Directory.customerForSso against the
 * real sso_customers table. The only thing not real is where the public key
 * comes from: a fixed keypair instead of a live fetch of
 * auth.b4rruf3t.com/.well-known/jwks.json, because a unit test that depends on
 * the public internet is a unit test that fails when a train goes into a tunnel.
 * That is the one seam sso-client already provides a constructor for.
 *
 * The five things the bank now owes:
 *
 *   lesson 1  NO TOKEN behaves exactly as it did before any of this existed
 *   lesson 2  a valid token is recognised, and resolves through sso_sub to
 *             THIS bank's customer id · not to the id in the query string
 *   lesson 3  an INVALID token is 401, permissive AND enforcing · because a
 *             caller holding a broken credential is not an anonymous caller
 *   lesson 4  a token minted for another app's audience is refused too · it
 *             is a real token, and it is not a token for us
 *   lesson 5  a valid token whose subject nobody linked is a VISITOR: a 200,
 *             the anonymous behaviour, and not mapped to anybody's account
 *
 * Lessons 3 and 5 are the pair worth reading together, because they are the
 * two ways this could have been built lazily and the difference between them
 * is the whole design. Collapse them one way and every broken credential is
 * silently served as anonymous, so a rotation that breaks every token in the
 * estate looks exactly like a quiet Tuesday. Collapse them the other way and
 * the bank 401s every signed-in shopper who follows a link to the public demo.
 *
 * Requires: docker compose up -d
 */
class BankAuthLessonTest {

    /** distinct from SsoIdentityLessonTest's customers on purpose · these two
     *  classes share a JVM, a database and a static identity seam, and a test
     *  that passes only when it runs second is not a lesson, it is a coin flip */
    static final long IGOR = 20, MAYA = 21;

    /** a distinctive amount · if it surfaces in the wrong response, a book leaked */
    static final BigDecimal IGOR_MONEY = new BigDecimal("512.00");

    static final String ISSUER = "https://auth.b4rruf3t.com";
    static final String KID = "bank-lesson-key";
    static final KeyPair PAIR = generateKeyPair();

    static final String IGOR_SUB = "sso|igor-2001", MAYA_SUB = "sso|maya-2002";

    /** a real person, signed into the estate, who does not bank here */
    static final String VISITOR_SUB = "sso|visitor-2999";

    static final HttpClient HTTP = HttpClient.newHttpClient();
    static HttpServer server;
    static int port;

    @BeforeAll
    static void boot() throws Exception {
        Shards.boot(
                System.getenv().getOrDefault("MINIBANK_SHARD0_URL", "jdbc:postgresql://localhost:5434/minibank"),
                System.getenv().getOrDefault("MINIBANK_SHARD1_URL", "jdbc:postgresql://localhost:5435/minibank"),
                "minibank", "minibank", 5);
        for (Shard s : Shards.all()) s.createSchema();
        Shards.nameRegions("eu", "uk");
        Directory.createOwnDatabase();          // applies db/directory/V5__sso_customers.sql
        Notifications.createOwnDatabase();      // lesson 1 asks /api/notifications for a 200

        wipeSsoLinks();
        Fixtures.resetShards();
        Shards.forCustomer(IGOR).createCustomer(IGOR, "igor");
        Shards.forCustomer(MAYA).createCustomer(MAYA, "maya");
        Products.ensureFor(IGOR);
        Products.ensureFor(MAYA);
        Shards.forCustomer(IGOR).transferLocal(UUID.randomUUID(), Shard.WORLD, IGOR, IGOR_MONEY);

        // linkSso evicts Directory's SSO_CACHE, so linking BEFORE anything
        // looks these subjects up is what keeps the raw TRUNCATE above from
        // leaving a stale cached answer behind. VISITOR_SUB is deliberately
        // never linked.
        Directory.linkSso(IGOR_SUB, IGOR);
        Directory.linkSso(MAYA_SUB, MAYA);

        // THE REAL VALIDATOR, with only the key source stubbed.
        HttpApi.identity(new BankAuth(ISSUER, BankAuth.AUDIENCE,
                kid -> KID.equals(kid) ? (RSAPublicKey) PAIR.getPublic() : null));
        server = HttpApi.start(0);
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void down() {
        // identity is a process-wide seam and this class moves it · put it
        // back, or every later test in the JVM inherits an issuer
        HttpApi.identity(null);
        if (server != null) server.stop(0);
    }

    @AfterEach
    void enforcementOffAgain() {
        // belt and braces around lesson 3b. Enforcement reads a system
        // property, a leaked one would make every later lesson in this JVM run
        // in a mode it never asked for, and the failure would land in whatever
        // class happens to sort next rather than here.
        System.clearProperty(Enforcement.PROPERTY);
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 1: NO TOKEN · the public demo behaves exactly as it did before the bank knew what a token was")
    void lesson1_noTokenIsUntouched() throws Exception {
        HttpResponse<String> igor = send("/api/statement?customer=" + IGOR, null);
        assertEquals(200, igor.statusCode());
        assertTrue(igor.body().contains("\"after\":\"512\""), "anonymous read still serves the demo");

        assertEquals("[]", get("/api/statement?customer=" + MAYA, null).trim(),
                "and an empty customer still has an empty book");

        // BYTE-IDENTICAL, not merely equivalent. The missing-parameter 400 is
        // the response most likely to have been quietly reworded by threading
        // identity through it, so it is asserted character for character.
        HttpResponse<String> none = send("/api/statement", null);
        assertEquals(400, none.statusCode());
        assertEquals("{\"error\":\"need ?customer=id\"}", none.body());
        assertEquals("application/json; charset=utf-8",
                none.headers().firstValue("Content-Type").orElse(""));

        // and nothing anywhere demands a credential · this is what "permissive"
        // means, and it is the only thing it means
        for (String path : new String[]{
                "/api/statement?customer=" + IGOR, "/api/accounts", "/api/notifications", "/metrics"}) {
            assertEquals(200, status(path, null), path + " must not be touched by identity work");
        }
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 2: a REAL token is recognised, and sso_sub resolves it to THIS bank's customer")
    void lesson2_validTokenResolvesThroughSsoSub() throws Exception {
        // no ?customer= at all · the signature is the only thing saying who
        // this is, which is the whole feature in one request
        HttpResponse<String> r = send("/api/statement", bearer(IGOR_SUB));
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"after\":\"512\""),
                "the token identified Igor and the bank served Igor: " + r.body());

        // and it travelled through the REAL link, in the real table · not
        // through a subject that happens to parse as a customer id
        assertEquals(IGOR, Directory.customerForSso(IGOR_SUB));
        assertEquals(MAYA, Directory.customerForSso(MAYA_SUB));

        // THE PRECEDENCE, proven against a real signature rather than a stub:
        // Maya's token plus Igor's id in the query string serves MAYA. This is
        // the assertion that fails if somebody ever "simplifies" caller() to
        // trust the parameter when one is supplied.
        String body = get("/api/statement?customer=" + IGOR, bearer(MAYA_SUB));
        assertEquals("[]", body.trim(), "Maya is Maya whatever the query string says: " + body);
        assertFalse(body.contains("512"), "Igor's money must not appear in Maya's response");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 3: an INVALID token is 401 · a caller holding a broken credential is not an anonymous caller")
    void lesson3_invalidTokenIsRefused() throws Exception {
        // Four ways a credential can be broken, and the bank must not care
        // which: an expired one, one signed by a key the issuer never had, one
        // minted by somebody else's issuer, and one that is simply not a JWT.
        record Bad(String what, String token) {}
        var broken = new Bad[]{
                new Bad("expired", jwt(IGOR_SUB, ISSUER, past(), BankAuth.AUDIENCE, KID, PAIR)),
                new Bad("forged signature", jwt(IGOR_SUB, ISSUER, future(), BankAuth.AUDIENCE, KID, generateKeyPair())),
                new Bad("another issuer", jwt(IGOR_SUB, "https://evil.example", future(), BankAuth.AUDIENCE, KID, PAIR)),
                new Bad("unknown key id", jwt(IGOR_SUB, ISSUER, future(), BankAuth.AUDIENCE, "no-such-kid", PAIR)),
                new Bad("not a JWT at all", "garbage.garbage.garbage"),
        };

        for (Bad b : broken) {
            HttpResponse<String> r = send("/api/statement?customer=" + IGOR, b.token());
            assertEquals(401, r.statusCode(),
                    b.what() + " must be refused, not quietly served as anonymous");

            // it must NOT have fallen through to the handler · if it had, the
            // body would be Igor's statement and the credential would have been
            // ignored rather than refused, which is the exact silent failure
            // this lesson exists to prevent
            assertFalse(r.body().contains("512"), b.what() + " reached the handler");

            // the refusal explains nothing. An operator gets the reason on
            // stderr; a caller on the far side of the trust boundary gets a
            // status and no oracle for what this bank would have accepted.
            assertEquals("{\"error\":\"invalid credential\"}", r.body());
            assertTrue(r.headers().firstValue("WWW-Authenticate").orElse("").startsWith("Bearer "),
                    "a 401 without WWW-Authenticate tells a client to give up rather than re-authenticate");
        }

        // A CREDENTIAL THAT IS NOT EVEN A BEARER ONE. Refused for the same
        // reason: something was presented, and it is not something this bank
        // can check. It is emphatically not "no credential".
        assertEquals(401, statusOfRawAuth("/api/statement?customer=" + IGOR, "Basic aWdvcjpodW50ZXIy"));
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 3b: an INVALID token is 401 under ENFORCEMENT too · the switch governs absence, never validity")
    void lesson3b_invalidTokenIsRefusedInBothModes() throws Exception {
        String expired = jwt(IGOR_SUB, ISSUER, past(), BankAuth.AUDIENCE, KID, PAIR);

        assertFalse(Enforcement.on(), "the switch must ship OFF · this is the default the estate deploys");
        assertEquals(401, status("/api/statement?customer=" + IGOR, expired), "permissive mode refuses it");

        // The switch is read per request, so a test can flip it in one JVM.
        // That is the entire reason Enforcement reads a system property as
        // well as an environment variable.
        System.setProperty(Enforcement.PROPERTY, "true");
        try {
            assertTrue(Enforcement.on());
            assertEquals(401, status("/api/statement?customer=" + IGOR, expired), "enforcing mode refuses it too");

            // WHAT THE SWITCH ACTUALLY CHANGES, and the only thing it changes:
            // whether ABSENCE is tolerated. Permissively this is the 200 from
            // lesson 1; enforcing, it is a refusal.
            assertEquals(401, status("/api/statement?customer=" + IGOR, null),
                    "enforcing, an /api/ request with no credential is refused");

            // and a GOOD token keeps working in enforcing mode · a switch that
            // broke authenticated callers would never be switched on
            assertTrue(get("/api/statement", bearer(IGOR_SUB)).contains("\"after\":\"512\""));

            // /metrics is deliberately outside the fence. Prometheus holds no
            // token, and an activation that blinds the monitoring on the same
            // afternoon it tightens the auth is an activation nobody can watch.
            assertEquals(200, status("/metrics", null), "the scrape endpoint stays open");
        } finally {
            System.clearProperty(Enforcement.PROPERTY);
        }

        // back to permissive, and the demo is untouched again · proving the
        // switch is genuinely a switch and not a one-way door
        assertFalse(Enforcement.on());
        assertEquals(200, status("/api/statement?customer=" + IGOR, null));
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 4: a token for the WRONG AUDIENCE is refused · a real token, and not a token for us")
    void lesson4_wrongAudienceIsRefused() throws Exception {
        // Signed by the estate's own issuer, with the estate's own key,
        // unexpired, naming a subject this bank has linked to a real customer.
        // Everything about it is genuine except who it was minted for.
        String mart = jwt(IGOR_SUB, ISSUER, future(), "mart.b4rruf3t.com", KID, PAIR);

        HttpResponse<String> r = send("/api/statement?customer=" + MAYA, mart);
        assertEquals(401, r.statusCode(), "the shop's credentials must not open the bank");
        assertEquals("{\"error\":\"invalid credential\"}", r.body());

        // THE HALF THAT MOVES MONEY: it must not have authenticated as Igor.
        // Refusing and mis-identifying are both failures; only one of them is
        // a breach, and the audience check is the only thing between them.
        assertFalse(r.body().contains("512"), "a mart token must never resolve to a bank customer");

        // an audience is matched exactly, not by prefix · sso-client proves
        // this at the unit level, and this asserts the bank inherited it
        assertEquals(401, status("/api/statement", jwt(IGOR_SUB, ISSUER, future(), "bank.b4rruf3t.com.evil", KID, PAIR)));
        assertEquals(401, status("/api/statement", jwt(IGOR_SUB, ISSUER, future(), "bank", KID, PAIR)));
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 5: a valid token nobody linked is a VISITOR · not an error, and not somebody else's account")
    void lesson5_unmappedSubjectIsAVisitor() throws Exception {
        assertNull(Directory.customerForSso(VISITOR_SUB), "nobody has linked this subject");
        String visitor = bearer(VISITOR_SUB);

        // NOT AN ERROR. The credential is perfect. It simply does not name a
        // customer of ours, which describes every signed-in shopper on the
        // estate and all 25 seeded demo customers.
        assertEquals(200, status("/api/statement?customer=" + MAYA, visitor),
                "a signed-in visitor with no account here is not a failure");
        assertEquals(200, status("/api/accounts", visitor));

        // NOT MAPPED TO ANYBODY. This is the assertion that catches a lookup
        // which returns a default, a first row, or the id it was handed when
        // it finds nothing · every one of which would hand a stranger a real
        // customer's book.
        assertEquals("[]", get("/api/statement?customer=" + MAYA, visitor).trim(),
                "the visitor sees Maya's empty book because Maya is what was asked for, not because they are Maya");
        assertFalse(get("/api/statement?customer=" + MAYA, visitor).contains("512"),
                "and never Igor's money, whose subject sorts next to theirs and is linked");

        // it behaves ANONYMOUSLY, which is the positive form of the same claim:
        // no identity was attached, so the request falls all the way back to
        // its own parameter, byte for byte as lesson 1
        HttpResponse<String> none = send("/api/statement", visitor);
        assertEquals(400, none.statusCode(), "with no parameter and no mapping there is still nobody to serve");
        assertEquals("{\"error\":\"need ?customer=id\"}", none.body());

        // INDISTINGUISHABLE from a linked subject from outside, and this is
        // the one place the old collapse-everything contract was right: a bank
        // that answered differently here would enumerate which humans hold
        // accounts at it, one token at a time.
        assertEquals(status("/api/accounts", bearer(IGOR_SUB)), status("/api/accounts", visitor));
        assertNotEquals(401, status("/api/accounts", visitor),
                "an unlinked subject must never be confused with a broken credential");
    }

    // ---------------------------------------------------------------- utils

    /** a genuine, currently valid bank token for a subject */
    private static String bearer(String sub) {
        return jwt(sub, ISSUER, future(), BankAuth.AUDIENCE, KID, PAIR);
    }

    /**
     * Mint a real RS256 JWT. Every parameter is a thing a token can be wrong
     * about, which is why they are all parameters: the lessons above are
     * mostly this method called with one field spoiled.
     */
    private static String jwt(String sub, String iss, long exp, String aud, String kid, KeyPair key) {
        String header = b64("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"" + kid + "\"}");
        String payload = b64("{\"sub\":\"" + sub + "\",\"iss\":\"" + iss + "\",\"exp\":" + exp
                + ",\"aud\":[\"" + aud + "\"]}");
        try {
            String input = header + "." + payload;
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign(key.getPrivate());
            s.update(input.getBytes(StandardCharsets.UTF_8));
            return input + "." + b64(s.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static long future() { return Instant.now().getEpochSecond() + 3600; }
    private static long past()   { return Instant.now().getEpochSecond() - 3600; }

    private static String b64(String s) { return b64(s.getBytes(StandardCharsets.UTF_8)); }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpRequest.Builder req(String path, String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return b;
    }

    private static HttpResponse<String> send(String path, String token) throws Exception {
        return HTTP.send(req(path, token).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String get(String path, String token) throws Exception {
        return send(path, token).body();
    }

    private static int status(String path, String token) throws Exception {
        return send(path, token).statusCode();
    }

    /** an Authorization header that is not "Bearer <jwt>" · lesson 3's last line */
    private static int statusOfRawAuth(String path, String header) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", header).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    /** the one table these lessons assert on · emptied before they run */
    private static void wipeSsoLinks() throws Exception {
        String url = System.getenv().getOrDefault("MINIBANK_DB_URL", "jdbc:postgresql://localhost:5433/minibank")
                .replaceFirst("/minibank$", "/minibank_directory");
        try (Connection c = DriverManager.getConnection(url, "minibank", "minibank");
             Statement st = c.createStatement()) {
            st.execute("SET lock_timeout = '4s'");
            st.execute("TRUNCATE sso_customers");
        }
    }
}
