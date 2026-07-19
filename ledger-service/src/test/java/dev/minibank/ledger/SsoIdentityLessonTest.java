package dev.minibank.ledger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSO IN THE BANK · WIRED, NOT REQUIRED · and the hole that opens on
 * activation day.
 *
 * The rollout is permissive, and the word means one specific thing: nothing
 * becomes newly REQUIRED. The public demo at bank.b4rruf3t.com has no accounts
 * and no logins, and it must keep working byte for byte. It does NOT mean a
 * credential that fails to verify is humoured · see lesson 3, which used to
 * assert the opposite and was wrong.
 *
 *   lesson 1  a valid bank-audience token attaches an identity, and the
 *             identified customer's book is what gets served
 *   lesson 2  no token · every route behaves EXACTLY as it did before SSO
 *             existed, same status and same bytes, and nothing 401s
 *   lesson 2b the same, for the seven MUTATING routes caller() was threaded
 *             through, because a dark launch that alters a write is worse
 *   lesson 3  a token minted for another app's audience is REFUSED, 401,
 *             with a body that says nothing about why
 *   lesson 3b a valid token whose subject nobody linked is a VISITOR: a 200,
 *             the anonymous behaviour, and not mapped to anybody's account
 *   lesson 4  a valid token for A plus ?customer=B serves A, never B ·
 *             on the read path AND on the write path
 *
 * Lessons 3 and 3b are the two halves of one idea and are best read together.
 * A broken credential is refused; a good credential that happens to name
 * nobody here is not. The first is a misconfiguration that should be loud, the
 * second is Tuesday.
 *
 * Lesson 4 passes trivially today (nothing is ever identified) and would
 * still pass the other three if the precedence were backwards. That is
 * exactly why it is worth writing now: a permissive rollout hides an IDOR
 * behind a set of green tests until the day enforcement is switched on and
 * real accounts exist.
 *
 * The tokens here are stubs · "<audience>:<subject>". Real RS256 validation
 * is sso-client's job and sso-client's tests. What the stub does NOT fake is
 * the second half of the lookup: subject → customer id goes through the real
 * Directory.customerForSso against the real sso_customers table, because that
 * half is this service's own code and its own migration.
 *
 * This is the ledger's mirror of dev.minibank.broker.BrokerIdentityLessonTest.
 * Two services, one rule, proven twice · deliberately, since the rule is
 * about whose money gets served and each service serves its own.
 *
 * Requires: docker compose up -d
 */
class SsoIdentityLessonTest {

    /** ALICE is even → shard0, BOB is odd → shard1 (the arithmetic router). */
    static final long ALICE = 10, BOB = 11;

    /** a distinctive amount · if it appears in Bob's response, a book leaked */
    static final BigDecimal ALICE_MONEY = new BigDecimal("777.00");

    /** stands in for BankAuth: a token is good only for THIS app's audience */
    static final String AUDIENCE = "bank.b4rruf3t.com";
    static final String ALICE_SUB = "sso|alice-0001", BOB_SUB = "sso|bob-0002";
    static final String UNLINKED_SUB = "sso|nobody-9999";

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
        // lesson 2 asks /api/notifications for a 200, so the notifications
        // database has to exist. It used to, by accident: OutboxLessonTest
        // creates it and sorts before this class, so when every test shared one
        // run this class inherited a world someone else had built. Splitting the
        // broker tests into their own CI job removed that neighbour and the
        // hidden dependency turned into a 500. A test that cannot stand up the
        // world it asserts on is not independent, it is just lucky.
        Notifications.createOwnDatabase();

        // start from an empty link table · these lessons assert on exactly who
        // a subject resolves to, and a run that inherits yesterday's links can
        // pass by luck (or fail on the customer_id UNIQUE constraint)
        wipeSsoLinks();

        Fixtures.resetShards();
        Shards.forCustomer(ALICE).createCustomer(ALICE, "alice");
        Shards.forCustomer(BOB).createCustomer(BOB, "bob");
        Products.ensureFor(ALICE);
        Products.ensureFor(BOB);
        // Alice has money. Bob's book is empty. If Bob ever sees 777,
        // somebody's statement leaked.
        Shards.forCustomer(ALICE).transferLocal(UUID.randomUUID(), Shard.WORLD, ALICE, ALICE_MONEY);

        Directory.linkSso(ALICE_SUB, ALICE);
        Directory.linkSso(BOB_SUB, BOB);

        HttpApi.identity(STUB);
        server = HttpApi.start(0);
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void down() {
        // put the service back the way the rest of the suite expects to find
        // it · identity is a process-wide seam and this is the only test that
        // moves it
        HttpApi.identity(null);
        if (server != null) server.stop(0);
    }

    /**
     * The stand-in for BankAuth + SsoClient: the shape of a real validator,
     * with the cryptography replaced by string equality and the DIRECTORY
     * LOOKUP left real.
     *
     * It answers with the same three verdicts BankAuth does, and the grouping
     * is the contract rather than an accident of this stub:
     *
     *   no header            Absent    · the demo, untouched
     *   wrong scheme         Rejected  · a credential that is not one
     *   wrong audience       Rejected  · a valid credential, not valid HERE
     *   linked subject       Known(id) · identified
     *   unlinked subject     Known(null) · a visitor, and indistinguishable
     *                                     from the line above, from outside
     *
     * BankAuthLessonTest runs the same lessons through the real RS256
     * validator with real signatures. This class keeps the stub because its
     * subject is the BANK's half · precedence, the IDOR, the untouched demo ·
     * and those are clearest when no cryptography is in the frame.
     */
    static final SsoIdentity STUB = header -> {
        if (header == null || header.isBlank()) return SsoIdentity.Verdict.ABSENT;
        if (!header.startsWith("Bearer ")) {
            return new SsoIdentity.Verdict.Rejected("not a Bearer credential");
        }
        String[] parts = header.substring(7).split(":", 2);     // "<audience>:<subject>"
        if (parts.length != 2 || !AUDIENCE.equals(parts[0])) {
            return new SsoIdentity.Verdict.Rejected("not this bank's audience");
        }
        try {
            return new SsoIdentity.Verdict.Known(parts[1], Directory.customerForSso(parts[1]));
        } catch (Exception e) {
            return new SsoIdentity.Verdict.Known(parts[1], null);  // an outage is not a bad token
        }
    };

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 1: a valid bank token attaches an identity · the caller need not say who they are")
    void lesson1_validTokenAttachesIdentity() throws Exception {
        // no ?customer= at all · the token is the only thing saying who this is
        HttpResponse<String> r = send("/api/statement", token(AUDIENCE, ALICE_SUB));
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"after\":\"777\""),
                "the token identified Alice and served Alice's statement: " + r.body());

        // and the link it travelled through is the real one, in the real table
        assertEquals(ALICE, Directory.customerForSso(ALICE_SUB));
        assertEquals(BOB, Directory.customerForSso(BOB_SUB));

        // a well-formed subject nobody has linked resolves to nothing · NOT to
        // an exception, and not to a distinguishable answer. An endpoint that
        // said "unknown subject" would be an oracle for which humans bank here.
        assertNull(Directory.customerForSso(UNLINKED_SUB));
        assertEquals(200, status("/api/statement?customer=" + BOB, token(AUDIENCE, UNLINKED_SUB)),
                "an unlinked subject is anonymous, and anonymous is not an error");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 2: no token · the public demo behaves EXACTLY as it did before SSO existed, and nothing 401s")
    void lesson2_anonymousIsUntouched() throws Exception {
        HttpResponse<String> alice = send("/api/statement?customer=" + ALICE, null);
        assertEquals(200, alice.statusCode());
        assertTrue(alice.body().contains("\"after\":\"777\""), "anonymous read still serves the demo");

        HttpResponse<String> bob = send("/api/statement?customer=" + BOB, null);
        assertEquals(200, bob.statusCode());
        assertEquals("[]", bob.body().trim(), "and still serves an empty book for an empty customer");

        // BYTE-IDENTICAL, not merely equivalent. The missing-parameter 400 is
        // the response most likely to have been quietly reworded by wiring
        // identity through it, so it is asserted character for character.
        HttpResponse<String> none = send("/api/statement", null);
        assertEquals(400, none.statusCode(), "no token and no parameter is the same 400 it always was");
        assertEquals("{\"error\":\"need ?customer=id\"}", none.body());
        assertEquals("application/json; charset=utf-8",
                none.headers().firstValue("Content-Type").orElse(""));

        HttpResponse<String> noPortfolio = send("/api/portfolio", null);
        assertEquals(400, noPortfolio.statusCode());
        assertEquals("{\"error\":\"need ?customer=id\"}", noPortfolio.body());

        // and nothing anywhere 401s · enforcement is a later, human decision
        for (String path : new String[]{
                "/api/statement?customer=" + ALICE, "/api/accounts", "/api/notifications", "/metrics"}) {
            int s = status(path, null);
            assertNotEquals(401, s, path + " must not demand a token during a permissive rollout");
            assertEquals(200, s, path + " must be untouched by identity work");
        }
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 2b: the MUTATING routes are untouched anonymously too · the ones caller() actually wrapped")
    void lesson2b_anonymousMutatingRoutesAreUntouched() throws Exception {
        // Lesson 2 proves the read routes are unchanged, which is the easy
        // half: they were not the ones edited. caller() was threaded through
        // seven WRITE routes, and a dark launch that quietly alters a write is
        // worse than one that alters a read. Every one of these must fail the
        // way it always failed for an anonymous caller · a validation 400,
        // never a 401, and never a different error.
        //
        // Bodies are deliberately incomplete: the assertion is about the SHAPE
        // of the refusal, not about moving money. Added after a review pointed
        // out that lesson 2 asserted "every route" and tested four GETs.
        record Route(String path, String body) {}
        var writes = new Route[]{
                new Route("/api/transfer", "{}"),
                new Route("/api/relocate", "{}"),
                new Route("/api/trade", "{}"),
                new Route("/api/mortgage", "{}"),
                new Route("/api/card", "{}"),
                new Route("/api/support", "{}"),
                new Route("/issuer/v1/instruments", "{}"),
        };
        for (Route r : writes) {
            int anon = statusOfPost(r.path(), null, r.body());
            assertNotEquals(401, anon, r.path() + " must never 401 during a permissive rollout");
            assertNotEquals(403, anon, r.path() + " must never 403 during a permissive rollout");
            assertTrue(anon < 500, r.path() + " anonymous must not become a server error · got " + anon);

            // and a token for another app is refused on every one of them,
            // uniformly · the choke point is HttpApi.handle, so a route cannot
            // be forgotten. The old version of this loop asserted the opposite
            // (wrongAud == anon); see lesson 3 for why that assertion was the
            // dangerous one.
            int wrongAud = statusOfPost(r.path(), token("mart.b4rruf3t.com", ALICE_SUB), r.body());
            assertEquals(401, wrongAud,
                    r.path() + " must refuse a credential that does not verify here, not humour it");

            // the write never happened · a refused request must not reach the
            // handler at all, which is what makes refusing safe to add to
            // money-moving routes
            assertNotEquals(anon, wrongAud,
                    r.path() + " a rejected credential must not take the anonymous path");
        }
    }

    @Test
    @DisplayName("lesson 3: a token for another app's audience is REFUSED · a credential that does not work here is not the same as no credential")
    void lesson3_wrongAudienceIsRefused() throws Exception {
        // a perfectly valid token, minted for the shop, presented to the bank
        String mart = token("mart.b4rruf3t.com", ALICE_SUB);

        // THIS LESSON CHANGED ITS ANSWER, and the reversal is the teaching.
        //
        // It used to assert 200: wrong audience attached no identity, the
        // request fell back to ?customer=, and nothing 401d because
        // "permissive" was read as "never refuse anything". That is the
        // reading that costs the most to discover. A mart token at the bank is
        // not an anonymous browser · it is an integration that is misconfigured
        // RIGHT NOW, and serving it a cheerful 200 means the misconfiguration
        // survives until somebody wonders why nobody is ever identified.
        //
        // Permissive means nothing is newly REQUIRED (lesson 2 still holds,
        // byte for byte). It does not mean a broken credential is humoured.
        assertEquals(401, status("/api/statement?customer=" + BOB, mart),
                "a token minted for another app is a credential that does not verify here");

        // the important half, unchanged and still the point: it did NOT
        // quietly authenticate as Alice. Refusing and mis-identifying are both
        // failures; only one of them moves money.
        assertFalse(get("/api/statement?customer=" + BOB, mart).contains("777"),
                "a mart token must never open the bank");

        // the refusal says nothing about WHY · no "wrong audience", no subject,
        // nothing an attacker can use to probe what this bank would accept
        assertEquals("{\"error\":\"invalid credential\"}", get("/api/statement?customer=" + BOB, mart));

        // and it is the SAME refusal with no parameter · a rejected credential
        // never reaches the handler, so it can never reach the handler's 400
        HttpResponse<String> none = send("/api/statement", mart);
        assertEquals(401, none.statusCode(), "the credential is refused before the parameter is even read");
        assertEquals("{\"error\":\"invalid credential\"}", none.body());
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 3b: a KNOWN visitor with no account here is not an error, and is not somebody else's account")
    void lesson3b_unlinkedSubjectIsAVisitor() throws Exception {
        // A real person, signed into the estate, who simply does not bank
        // here. The estate has 25 demo customers and none of them are linked,
        // so this is the ordinary case rather than the exotic one.
        String visitor = token(AUDIENCE, UNLINKED_SUB);

        assertNull(Directory.customerForSso(UNLINKED_SUB), "nobody has linked this subject");

        // NOT a 401. The credential is perfect; it just does not name a
        // customer of ours. Refusing here would mean the bank 401s every
        // signed-in shopper who follows a link to the public demo.
        assertEquals(200, status("/api/statement?customer=" + BOB, visitor),
                "a valid token nobody linked is a visitor, and a visitor is not an error");

        // NOT mapped to anyone either · the request falls all the way back to
        // the parameter, exactly as an anonymous one would. The failure this
        // guards against is a lookup that returns a default or a first row on
        // a miss and hands a stranger somebody's book.
        assertEquals("[]", get("/api/statement?customer=" + BOB, visitor).trim(),
                "the visitor got Bob's empty book because Bob is what was asked for, not because they are Bob");
        assertFalse(get("/api/statement?customer=" + ALICE, visitor).isEmpty());
        assertTrue(get("/api/statement?customer=" + ALICE, visitor).contains("777"),
                "and asking for Alice by id behaves anonymously too · no identity was attached to override it");

        // INDISTINGUISHABLE from a linked subject, from outside. This is the
        // oracle argument, and it is the one part of the old collapsed
        // contract that survives: if an unlinked subject answered differently
        // from a linked one, this endpoint would enumerate which humans hold
        // accounts at this bank.
        assertEquals(status("/api/accounts", token(AUDIENCE, ALICE_SUB)),
                status("/api/accounts", visitor),
                "linked and unlinked subjects must not be tellable apart by status");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 4: THE IDOR · a valid token plus someone else's id serves the token's owner, never the id")
    void lesson4_tokenBeatsTheParameter() throws Exception {
        // Bob, authenticated, asking for Alice's statement by id
        String asBob = token(AUDIENCE, BOB_SUB);
        String body = get("/api/statement?customer=" + ALICE, asBob);

        assertEquals("[]", body.trim(),
                "Bob is Bob whatever the query string says · this is the whole lesson: " + body);
        assertFalse(body.contains("777"), "Alice's money must not appear in Bob's response");

        // the mirror image: Alice, authenticated, asking for Bob's · she gets
        // her own book, so the override is a substitution and not a blanking
        String asAlice = token(AUDIENCE, ALICE_SUB);
        assertTrue(get("/api/statement?customer=" + BOB, asAlice).contains("\"after\":\"777\""),
                "the token's owner is served, not an empty response");

        // THE WRITE PATH, which is where this stops being about privacy and
        // starts being about money. Alice's token, Bob's id in the body: the
        // hold must land on Alice's card, and Bob's must not move at all.
        BigDecimal bobHoldsBefore = Shards.forCustomer(BOB).balance(BOB + Products.HOLDS);
        String tx = UUID.randomUUID().toString();
        String reply = post("/api/card", asAlice,
                "{\"action\":\"authorize\",\"customer\":" + BOB + ",\"tx\":\"" + tx + "\",\"amount\":\"25.00\"}");
        assertTrue(reply.contains("\"result\":\"ok\""), "the authorization was accepted: " + reply);

        assertEquals(0, new BigDecimal("25.00").compareTo(Shards.forCustomer(ALICE).balance(ALICE + Products.HOLDS)),
                "the hold belongs to the token's owner, not to the customer field in the body");
        assertEquals(0, bobHoldsBefore.compareTo(Shards.forCustomer(BOB).balance(BOB + Products.HOLDS)),
                "a forged customer field must not be able to spend somebody else's card");
    }

    // ------------------------------------------------------------------
    private static String token(String audience, String subject) {
        return audience + ":" + subject;
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

    /** the status of a POST · lesson 2b cares about the shape of the refusal */
    private static int statusOfPost(String path, String token, String body) throws Exception {
        return HTTP.send(req(path, token).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private static String post(String path, String token, String body) throws Exception {
        return HTTP.send(req(path, token).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
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
