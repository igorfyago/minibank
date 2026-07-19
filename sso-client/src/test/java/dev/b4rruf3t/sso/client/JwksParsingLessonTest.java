package dev.b4rruf3t.sso.client;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How a JWKS document is read, and what happens when the endpoint serving it
 * goes down. Both were defects found by porting this client to Python.
 */
class JwksParsingLessonTest {

    // --------------------------------------------------------------- DEFECT 3
    // The body used to be split on the literal text {"kty":"RSA", which worked
    // only because the issuer happens to emit compact JSON with kty first. A
    // proxy or CDN that reformatted the response would have yielded zero keys
    // and silently failed every validation on the estate.

    @Test
    void aPrettyPrintedJwksStillParses() {
        Jwks jwks = new Jwks("http://unused");
        assertTrue(jwks.load(prettyJwks("k1")), "whitespace must not defeat the parser");
        assertNotNull(jwks.getPublicKey("k1"));
    }

    @Test
    void aJwksWhoseMembersAreOrderedDifferentlyStillParses() {
        // kty last instead of first: legal JSON, and the old split found nothing.
        RSAPublicKey key = (RSAPublicKey) PAIR.getPublic();
        String body = "{\"keys\":[{"
                    + "\"kid\":\"k2\","
                    + "\"n\":\"" + n(key) + "\","
                    + "\"e\":\"" + e(key) + "\","
                    + "\"use\":\"sig\","
                    + "\"kty\":\"RSA\""
                    + "}]}";
        Jwks jwks = new Jwks("http://unused");
        assertTrue(jwks.load(body));
        assertNotNull(jwks.getPublicKey("k2"));
    }

    @Test
    void aJwksCarryingSeveralKeysLoadsThemAll() {
        Jwks jwks = new Jwks("http://unused");
        RSAPublicKey a = (RSAPublicKey) PAIR.getPublic();
        RSAPublicKey b = (RSAPublicKey) OTHER.getPublic();
        String body = "{\"keys\":["
                    + jwk("rotating-out", a) + "," + jwk("rotating-in", b) + "]}";
        assertTrue(jwks.load(body));
        assertNotNull(jwks.getPublicKey("rotating-out"), "the old key must stay usable during rotation");
        assertNotNull(jwks.getPublicKey("rotating-in"));
    }

    @Test
    void nonRsaEntriesAreSkippedRatherThanBreakingTheDocument() {
        RSAPublicKey key = (RSAPublicKey) PAIR.getPublic();
        String body = "{\"keys\":["
                    + "{\"kty\":\"EC\",\"kid\":\"ec1\",\"crv\":\"P-256\",\"x\":\"a\",\"y\":\"b\"},"
                    + jwk("rsa1", key) + "]}";
        Jwks jwks = new Jwks("http://unused");
        assertTrue(jwks.load(body), "one unusable entry must not discard the usable ones");
        assertNotNull(jwks.getPublicKey("rsa1"));
        assertNull(jwks.getPublicKey("ec1"));
    }

    @Test
    void garbageAndEmptyDocumentsAreRefusedWithoutThrowing() {
        Jwks jwks = new Jwks("http://unused");
        assertFalse(jwks.load("not json at all"));
        assertFalse(jwks.load("{}"));
        assertFalse(jwks.load("{\"keys\":[]}"));
        assertFalse(jwks.load("{\"keys\":\"not-an-array\"}"));
        assertFalse(jwks.load(""));
    }

    @Test
    void anEntryMissingItsModulusIsSkipped() {
        String body = "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"broken\",\"e\":\"AQAB\"}]}";
        Jwks jwks = new Jwks("http://unused");
        assertFalse(jwks.load(body));
        assertNull(jwks.getPublicKey("broken"));
    }

    // --------------------------------------------------------------- DEFECT 4
    // After a FAILED refresh the cache used to be re-read without re-checking
    // expiry, so an expired key kept validating for as long as the endpoint was
    // down. The replacement is a bounded grace window, and the boundary is what
    // matters: a key must not be servable forever.

    @Test
    void anUnreachableEndpointDoesNotResurrectAnUnknownKey() {
        // Nothing cached, endpoint refuses: the answer is no, not an exception.
        Jwks jwks = new Jwks("http://127.0.0.1:1/.well-known/jwks.json");
        assertNull(jwks.getPublicKey("anything"));
    }

    @Test
    void aFreshlyLoadedKeyIsServedFromCacheWithoutTouchingTheNetwork() {
        // The URL is unroutable, so if this needed the network it would fail.
        Jwks jwks = new Jwks("http://127.0.0.1:1/.well-known/jwks.json");
        assertTrue(jwks.load(jwksFor("k1")));
        assertNotNull(jwks.getPublicKey("k1"), "a live key must not require a fetch");
    }

    @Test
    void theGraceWindowIsBoundedRatherThanUnlimited() {
        // The defect was that there was NO bound. Pin that a bound exists and
        // that it is finite, so a future edit cannot quietly restore "forever".
        long grace = graceSeconds();
        assertTrue(grace > 0, "some grace, or one blip logs out the whole estate");
        assertTrue(grace <= 24 * 3600,
                   "the grace must be bounded; unlimited is how a revoked key lives on");
    }

    // ---------------------------------------------------------------- utils

    private static final KeyPair PAIR = keyPair();
    private static final KeyPair OTHER = keyPair();

    private static String jwksFor(String kid) {
        return "{\"keys\":[" + jwk(kid, (RSAPublicKey) PAIR.getPublic()) + "]}";
    }

    private static String prettyJwks(String kid) {
        RSAPublicKey key = (RSAPublicKey) PAIR.getPublic();
        return "{\n"
             + "  \"keys\" : [ {\n"
             + "    \"kty\" : \"RSA\",\n"
             + "    \"kid\" : \"" + kid + "\",\n"
             + "    \"n\" : \"" + n(key) + "\",\n"
             + "    \"e\" : \"" + e(key) + "\"\n"
             + "  } ]\n"
             + "}";
    }

    private static String jwk(String kid, RSAPublicKey key) {
        return "{\"kty\":\"RSA\",\"kid\":\"" + kid + "\",\"n\":\"" + n(key)
             + "\",\"e\":\"" + e(key) + "\"}";
    }

    private static String n(RSAPublicKey k) { return b64(k.getModulus().toByteArray()); }
    private static String e(RSAPublicKey k) { return b64(k.getPublicExponent().toByteArray()); }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Read the configured grace so the test pins behaviour, not a literal. */
    private static long graceSeconds() {
        try {
            var f = Jwks.class.getDeclaredField("STALE_GRACE_SECONDS");
            f.setAccessible(true);
            return f.getLong(null);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("the grace window must remain a named, findable choice", ex);
        }
    }

    private static KeyPair keyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
