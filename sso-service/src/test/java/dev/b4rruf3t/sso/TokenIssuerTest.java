package dev.b4rruf3t.sso;

import dev.b4rruf3t.sso.client.SsoClient;
import dev.b4rruf3t.sso.client.SsoUser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The round-trip lesson: a token issued by the SSO service must validate
 * in any app using the sso-client library. This test wires the real issuer
 * to the real client through the public key — the exact path a request
 * takes from bank to mart in production.
 */
class TokenIssuerTest {

    private static final String ISSUER = "https://auth.b4rruf3t.com";

    private final KeyManager keys = new KeyManager();
    private final TokenIssuer issuer = new TokenIssuer(keys, ISSUER);
    // The client resolves keys straight from the test keypair — no HTTP needed.
    private final SsoClient client = new SsoClient(ISSUER, kid -> keys.publicKey());

    @Test
    void issuedTokenValidatesRoundTrip() {
        String jwt = issuer.issueAccessToken(
            "usr_abc123", List.of("bank.b4rruf3t.com", "mart.b4rruf3t.com"),
            "Igor", "igor@b4rruf3t.com", 900);

        Optional<SsoUser> user = client.validateToken(jwt, "bank.b4rruf3t.com");

        assertTrue(user.isPresent(), "a token the service just issued must validate");
        assertEquals("usr_abc123", user.get().sub());
        assertEquals("Igor", user.get().name());
        assertEquals("igor@b4rruf3t.com", user.get().email());
    }

    @Test
    void tokenHasThreePartsAndRs256Header() {
        String jwt = issuer.issueAccessToken(
            "usr_x", List.of("bank.b4rruf3t.com"), "A", "a@b.c", 900);

        String[] parts = jwt.split("\\.");
        assertEquals(3, parts.length, "a JWT is header.payload.signature");

        String header = new String(java.util.Base64.getUrlDecoder().decode(parts[0]));
        assertTrue(header.contains("\"alg\":\"RS256\""), "header declares RS256");
        assertTrue(header.contains("\"kid\":\""), "header carries the key id");
        assertFalse(parts[2].isEmpty(), "signature must not be empty");
    }

    @Test
    void wrongAudienceIsRejected() {
        String jwt = issuer.issueAccessToken(
            "usr_abc", List.of("bank.b4rruf3t.com"), "Igor", "i@b.c", 900);

        // 'pay' is not in the token's aud list — a substring match must not pass
        Optional<SsoUser> user = client.validateToken(jwt, "pay.b4rruf3t.com");

        assertTrue(user.isEmpty(), "a token for bank must not open pay");
    }

    @Test
    void audienceSubstringDoesNotLeak() {
        // adversarial: 'bank.b4rruf3t.com' contains 'bank' — a contains() check
        // would accept 'bank.evil.com'. Ours compares exact elements.
        String jwt = issuer.issueAccessToken(
            "usr_abc", List.of("bank.b4rruf3t.com"), "Igor", "i@b.c", 900);

        assertTrue(client.validateToken(jwt, "bank").isEmpty(),
            "bare 'bank' is not an audience, even though it is a substring");
        assertTrue(client.validateToken(jwt, "bank.b4rruf3t.com.evil").isEmpty(),
            "a suffixed lookalike is not an audience");
    }

    @Test
    void expiredTokenIsRejected() {
        // ttl of -1: already expired when issued
        String jwt = issuer.issueAccessToken(
            "usr_abc", List.of("bank.b4rruf3t.com"), "Igor", "i@b.c", -1);

        Optional<SsoUser> user = client.validateToken(jwt, "bank.b4rruf3t.com");

        assertTrue(user.isEmpty(), "an expired token must not validate");
    }

    @Test
    void wrongIssuerIsRejected() {
        TokenIssuer other = new TokenIssuer(keys, "https://evil.example.com");
        String jwt = other.issueAccessToken(
            "usr_abc", List.of("bank.b4rruf3t.com"), "Igor", "i@b.c", 900);

        Optional<SsoUser> user = client.validateToken(jwt, "bank.b4rruf3t.com");

        assertTrue(user.isEmpty(), "a token from another issuer must not validate");
    }

    @Test
    void tamperedPayloadIsRejected() {
        String jwt = issuer.issueAccessToken(
            "usr_abc", List.of("bank.b4rruf3t.com"), "Igor", "i@b.c", 900);

        String[] parts = jwt.split("\\.");
        // Forge a payload that says the user is an admin of everything
        String forgedPayload = "{\"iss\":\"" + ISSUER + "\",\"sub\":\"usr_admin\",\"aud\":[\"bank.b4rruf3t.com\"],\"exp\":9999999999}";
        String forged = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(forgedPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String tampered = parts[0] + "." + forged + "." + parts[2];

        Optional<SsoUser> user = client.validateToken(tampered, "bank.b4rruf3t.com");

        assertTrue(user.isEmpty(), "a swapped payload breaks the signature — must not validate");
    }

    @Test
    void garbageIsRejectedNotThrown() {
        assertTrue(client.validateToken("not-a-jwt", "bank.b4rruf3t.com").isEmpty());
        assertTrue(client.validateToken("", "bank.b4rruf3t.com").isEmpty());
        assertTrue(client.validateToken("a.b", "bank.b4rruf3t.com").isEmpty());
        assertTrue(client.validateToken("a.b.c.d", "bank.b4rruf3t.com").isEmpty());
    }

    @Test
    void jwksJsonCarriesThePublicKey() {
        String jwks = keys.toJwksJson();

        assertTrue(jwks.contains("\"kty\":\"RSA\""), "declares an RSA key");
        assertTrue(jwks.contains("\"kid\":\"" + keys.keyId() + "\""), "carries the key id");
        assertTrue(jwks.contains("\"n\":\""), "carries the modulus");
        assertTrue(jwks.contains("\"e\":\""), "carries the exponent");
        assertTrue(jwks.contains("\"alg\":\"RS256\""), "declares the algorithm");
    }

    @Test
    void everyTokenGetsAUniqueJti() {
        String a = issuer.issueAccessToken("usr_x", List.of("bank.b4rruf3t.com"), "A", "a@b.c", 900);
        String b = issuer.issueAccessToken("usr_x", List.of("bank.b4rruf3t.com"), "A", "a@b.c", 900);

        String jtiA = payloadField(a, "jti");
        String jtiB = payloadField(b, "jti");
        assertNotEquals(jtiA, jtiB, "the jti is what lets us revoke one token without revoking all");
    }

    private String payloadField(String jwt, String field) {
        String payload = new String(java.util.Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"")
            .matcher(payload);
        return m.find() ? m.group(1) : null;
    }
}
