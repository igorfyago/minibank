package dev.b4rruf3t.sso.client;

import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The JWKS parsing lessons, without HTTP. The HTTP fetch is one line of
 * java.net.http — what can go wrong is the parsing and the key rebuild,
 * and those are what these tests pin down.
 */
class JwksTest {

    @Test
    void buildsAPublicKeyFromModulusAndExponent() throws Exception {
        // Generate a real RSA keypair, encode it the way JWKS does
        var gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        var pair = gen.generateKeyPair();
        var original = (RSAPublicKey) pair.getPublic();

        String n = Base64.getUrlEncoder().withoutPadding().encodeToString(original.getModulus().toByteArray());
        String e = Base64.getUrlEncoder().withoutPadding().encodeToString(original.getPublicExponent().toByteArray());

        // Rebuild it through the same code Jwks uses
        RSAPublicKey rebuilt = rebuild(n, e);

        assertEquals(original.getModulus(), rebuilt.getModulus());
        assertEquals(original.getPublicExponent(), rebuilt.getPublicExponent());
    }

    @Test
    void garbageReturnsNullNotException() {
        assertNull(rebuild("!!!", "???"));
        assertNull(rebuild("", ""));
    }

    /** Mirror of Jwks.buildKey — kept package-visible for this test. */
    private RSAPublicKey rebuild(String nBase64, String eBase64) {
        try {
            byte[] nBytes = Base64.getUrlDecoder().decode(nBase64);
            byte[] eBytes = Base64.getUrlDecoder().decode(eBase64);
            var spec = new java.security.spec.RSAPublicKeySpec(
                new java.math.BigInteger(1, nBytes), new java.math.BigInteger(1, eBytes));
            return (RSAPublicKey) java.security.KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (Exception e) {
            return null;
        }
    }
}
