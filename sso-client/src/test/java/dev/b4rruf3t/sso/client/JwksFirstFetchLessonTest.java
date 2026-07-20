package dev.b4rruf3t.sso.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE FIRST FETCH MUST ACTUALLY FETCH.
 *
 * The other Jwks tests call load() directly, so they exercise the parsing but
 * never the path a real caller takes: getPublicKey on an empty cache, which
 * goes through refresh(). That gap hid a bug that broke token validation on
 * every Java service at once · refresh() rate-limits itself with
 * `now - lastAttempt < interval`, and lastAttempt was seeded with
 * Long.MIN_VALUE, so `now - Long.MIN_VALUE` overflowed to a negative number
 * below the interval and the first fetch was skipped forever. The cache stayed
 * empty, getPublicKey returned null, and every token was rejected.
 *
 * This test stands up a real JWKS endpoint and asks for a key the only way a
 * caller ever does, so the overflow cannot come back unseen.
 */
class JwksFirstFetchLessonTest {

    @Test
    void aFreshJwksFetchesOnTheVeryFirstAsk() throws Exception {
        KeyPair pair = keyPair();
        RSAPublicKey pub = (RSAPublicKey) pair.getPublic();
        String kid = "sso-test-1";
        AtomicInteger hits = new AtomicInteger();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/jwks.json", ex -> {
            hits.incrementAndGet();
            byte[] body = jwksBody(kid, pub).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            String url = "http://localhost:" + server.getAddress().getPort() + "/jwks.json";
            Jwks jwks = new Jwks(url);

            // THE ASSERTION. Before the fix this was null, because refresh() never ran.
            RSAPublicKey got = jwks.getPublicKey(kid);
            assertNotNull(got, "the first getPublicKey must trigger a real fetch");
            assertEquals(pub.getModulus(), got.getModulus(), "and return the published key");
            assertTrue(hits.get() >= 1, "the endpoint must actually have been called");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anUnknownKidStillReturnsNullWithoutHammeringTheEndpoint() throws Exception {
        KeyPair pair = keyPair();
        String kid = "sso-test-1";
        AtomicInteger hits = new AtomicInteger();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/jwks.json", ex -> {
            hits.incrementAndGet();
            byte[] body = jwksBody(kid, (RSAPublicKey) pair.getPublic()).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            Jwks jwks = new Jwks("http://localhost:" + server.getAddress().getPort() + "/jwks.json");
            assertNull(jwks.getPublicKey("nobody"), "an unknown kid resolves to nothing");
            // one fetch happened; the rate limit means a second immediate miss
            // does not fetch again, so junk kids cannot become an amplifier
            jwks.getPublicKey("nobody-again");
            assertEquals(1, hits.get(), "the second miss must be rate-limited, not a fresh fetch");
        } finally {
            server.stop(0);
        }
    }

    private static String jwksBody(String kid, RSAPublicKey key) {
        String n = b64(key.getModulus().toByteArray());
        String e = b64(key.getPublicExponent().toByteArray());
        return "{\"keys\":[{\"kty\":\"RSA\",\"use\":\"sig\",\"kid\":\"" + kid
             + "\",\"n\":\"" + n + "\",\"e\":\"" + e + "\"}]}";
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }
}
