package dev.minibank.broker;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSO, WIRED BUT NOT ENFORCING · and the hole that opens on activation day.
 *
 * The rollout is permissive: validate a token if one is present, attach the
 * identity, never 401 yet. Public demo traffic has no accounts and must keep
 * working exactly as it does today.
 *
 * The directive asks every app to prove three things. Those are lessons 1 to
 * 3 here. Lesson 4 is the one they do not ask for, and it is the one that
 * leaks money:
 *
 *   lesson 1  a valid token attaches an identity
 *   lesson 2  no token · the service behaves exactly as it did before
 *   lesson 3  a token for another app's audience attaches nothing
 *   lesson 4  a valid token PLUS somebody else's customer id in the request
 *             serves the token's owner, never the id in the request
 *
 * Lesson 4 passes trivially today (nothing is ever identified) and would
 * still pass the other three tests if the precedence were backwards. That is
 * exactly why it is worth writing now: a permissive rollout hides an IDOR
 * behind a set of green tests until the day enforcement is switched on and
 * real accounts exist.
 *
 * The tokens here are stubs. Real RS256 validation is sso-client's job and
 * sso-client's tests; what this file proves is which book gets served, which
 * is the part that is this service's responsibility.
 *
 * Requires: docker compose up -d
 */
class BrokerIdentityLessonTest {

    static final long ALICE = 10, BOB = 11;
    static final BrokerLessonTest.StubVenue VENUE = new BrokerLessonTest.StubVenue();
    static final HttpClient HTTP = HttpClient.newHttpClient();

    /** stands in for BankAuth: a token is good only for this app's audience */
    static final String AUDIENCE = "bank.b4rruf3t.com";
    static HttpServer server;
    static int port;

    @BeforeAll
    static void boot() throws Exception {
        BrokerDb.createOwnDatabase();
        // start from an empty book · these lessons assert on exact responses,
        // and a test that inherits yesterday's positions can pass by luck
        dev.minibank.ledger.Fixtures.resetBrokerDb();
        Catalog.seed();
        Broker broker = new Broker(VENUE);

        CallerIdentity identity = header -> {
            // shape of a real validator: Bearer, then audience, then subject
            if (header == null || !header.startsWith("Bearer ")) return Optional.empty();
            String[] parts = header.substring(7).split(":");   // "<audience>:<customerId>"
            if (parts.length != 2 || !AUDIENCE.equals(parts[0])) return Optional.empty();
            try {
                return Optional.of(Long.parseLong(parts[1]));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        };

        server = new BrokerApi(broker, identity).start(0);
        port = server.getAddress().getPort();

        VENUE.fillAt("100.00");
        // Alice holds bitcoin. Bob holds nothing. If Bob ever sees a
        // position, somebody's book leaked.
        broker.place("id-alice", ALICE, "BTC", "buy", null, dec("500.00"), "market", null);
    }

    @AfterAll
    static void down() {
        if (server != null) server.stop(0);
    }

    @BeforeEach
    void freshVenue() {
        VENUE.reset();
        VENUE.fillAt("100.00");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 1: a valid token attaches an identity · the caller need not say who they are")
    void lesson1_validTokenAttachesIdentity() throws Exception {
        // no customer parameter at all · the token is the only thing saying who
        String body = get("/api/positions", token(AUDIENCE, ALICE));
        assertTrue(body.contains("\"symbol\":\"BTC\""),
                "the token identified Alice and served Alice's book: " + body);
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 2: no token · the public demo works exactly as it did before SSO existed")
    void lesson2_anonymousStillWorks() throws Exception {
        String alice = get("/api/positions?customer=" + ALICE, null);
        assertTrue(alice.contains("\"symbol\":\"BTC\""), "anonymous read still serves the demo");

        String bob = get("/api/positions?customer=" + BOB, null);
        assertEquals("[]", bob.trim(), "and still serves an empty book for an empty customer");

        // and nothing 401s · enforcement is a later, human decision
        assertEquals(200, status("/api/positions?customer=" + ALICE, null),
                "a missing token must not be an error while the rollout is permissive");
        assertEquals(200, status("/api/instruments", null));
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 3: a token for another app's audience attaches nothing · and still is not an error")
    void lesson3_wrongAudienceAttachesNothing() throws Exception {
        // a perfectly valid token, minted for the shop, presented to the bank
        String wrong = token("mart.b4rruf3t.com", ALICE);

        assertEquals(200, status("/api/positions?customer=" + BOB, wrong),
                "wrong audience is not a 401 during a permissive rollout");
        assertEquals("[]", get("/api/positions?customer=" + BOB, wrong).trim(),
                "it attached no identity, so the request fell back to the parameter");

        // the important half: it did NOT quietly authenticate as Alice
        assertFalse(get("/api/positions?customer=" + BOB, wrong).contains("BTC"),
                "a mart token must never open the bank");
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("lesson 4: THE IDOR · a valid token plus someone else's id serves the token's owner, never the id")
    void lesson4_tokenBeatsTheParameter() throws Exception {
        // Bob, authenticated, asking for Alice's book by id
        String asBob = token(AUDIENCE, BOB);
        String body = get("/api/positions?customer=" + ALICE, asBob);

        assertEquals("[]", body.trim(),
                "Bob is Bob whatever the query string says · this is the whole lesson: " + body);
        assertFalse(body.contains("BTC"), "Alice's position must not appear in Bob's response");

        // the same rule on the write path · an order is placed for the token's
        // owner, so a forged customer field cannot spend somebody else's money
        String placed = post("/api/orders", asBob,
                "{\"clientOrderId\":\"idor-attempt\",\"customer\":" + ALICE
                + ",\"symbol\":\"BTC\",\"side\":\"buy\",\"notional\":\"10.00\",\"type\":\"market\"}");
        assertTrue(placed.contains("\"result\""), "the order was accepted: " + placed);

        try (Connection c = BrokerDb.open()) {
            Broker.Order o = Broker.byClientId(c, "idor-attempt");
            assertEquals(BOB, o.customerId(),
                    "the order belongs to the token's owner, not to the customer field in the body");
        }
    }

    // ------------------------------------------------------------------
    private static BigDecimal dec(String v) { return new BigDecimal(v); }

    private static String token(String audience, long customerId) {
        return audience + ":" + customerId;
    }

    private static HttpRequest.Builder req(String path, String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return b;
    }

    private static String get(String path, String token) throws Exception {
        return HTTP.send(req(path, token).build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    private static int status(String path, String token) throws Exception {
        return HTTP.send(req(path, token).build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private static String post(String path, String token, String body) throws Exception {
        return HTTP.send(req(path, token).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }
}
