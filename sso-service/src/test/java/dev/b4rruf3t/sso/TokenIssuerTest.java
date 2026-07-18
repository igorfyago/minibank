package dev.b4rruf3t.sso;

import org.junit.jupiter.api.Test;

import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The issuer-side lessons, proven without the client library: this module
 * must not depend on sso-client (the client validates tokens; the service
 * mints them — the dependency points one way, and tests respect it).
 * Signature verification here uses java.security directly, the same JDK
 * calls the client makes.
 */
class TokenIssuerTest {

    private static final String ISSUER = "https://auth.b4rruf3t.com";

    private final KeyManager keys = new KeyManager();
    private final TokenIssuer issuer = new TokenIssuer(keys, ISSUER);

    @Test
    void tokenHasThreePartsAndRs256Header() {
        String jwt = issuer.issueAccessToken(
            "usr_x", List.of("bank.b4rruf3t.com"), "A", "a@b.c", 900);

        String[] parts = jwt.split("\\.");
        assertEquals(3, parts.length, "a JWT is header.payload.signature");

        String header = new String(Base64.getUrlDecoder().decode(parts[0]));
        assertTrue(header.contains("\"alg\":\"RS256\""), "header declares RS256");
        assertTrue(header.contains("\"kid\":\""), "header carries the key id");
        assertFalse(parts[2].isEmpty(), "signature must not be empty");
    }

    @Test
    void signatureVerifiesAgainstThePublicKey() throws Exception {
        String jwt = issuer.issueAccessToken(
            "usr_abc123", List.of("bank.b4rruf3t.com"), "Igor", "i@b.c", 900);

        String[] parts = jwt.split("\\.");
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initVerify(keys.publicKey());
        sig.update((parts[0] + "." + parts[1]).getBytes("UTF-8"));

        assertTrue(sig.verify(Base64.getUrlDecoder().decode(parts[2])),
            "any app holding the public key can prove this token is ours — that is the whole design");
    }

    @Test
    void payloadCarriesTheClaims() {
        String jwt = issuer.issueAccessToken(
            "usr_abc", List.of("bank.b4rruf3t.com", "mart.b4rruf3t.com"), "Igor", "i@b.c", 900);

        String payload = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
        assertTrue(payload.contains("\"iss\":\"" + ISSUER + "\""));
        assertTrue(payload.contains("\"sub\":\"usr_abc\""));
        assertTrue(payload.contains("\"aud\":[\"bank.b4rruf3t.com\",\"mart.b4rruf3t.com\"]"));
        assertTrue(payload.contains("\"exp\":"));
        assertTrue(payload.contains("\"jti\":\"tok_"));
    }

    @Test
    void tamperedPayloadBreaksTheSignature() throws Exception {
        String jwt = issuer.issueAccessToken(
            "usr_abc", List.of("bank.b4rruf3t.com"), "Igor", "i@b.c", 900);

        String[] parts = jwt.split("\\.");
        String forgedPayload = "{\"iss\":\"" + ISSUER + "\",\"sub\":\"usr_admin\",\"exp\":9999999999}";
        String forged = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(forgedPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initVerify(keys.publicKey());
        sig.update((parts[0] + "." + forged).getBytes("UTF-8"));

        assertFalse(sig.verify(Base64.getUrlDecoder().decode(parts[2])),
            "swap the payload and the signature no longer matches — forgery fails");
    }

    @Test
    void expiryIsInTheFuture() {
        String jwt = issuer.issueAccessToken(
            "usr_abc", List.of("bank.b4rruf3t.com"), "Igor", "i@b.c", 900);

        String payload = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
        long exp = Long.parseLong(payload.replaceAll(".*\"exp\":(\\d+).*", "$1"));
        assertTrue(exp > Instant.now().getEpochSecond(), "a fresh token must not be born dead");
    }

    @Test
    void everyTokenGetsAUniqueJti() {
        String a = issuer.issueAccessToken("usr_x", List.of("bank.b4rruf3t.com"), "A", "a@b.c", 900);
        String b = issuer.issueAccessToken("usr_x", List.of("bank.b4rruf3t.com"), "A", "a@b.c", 900);

        String jtiA = payloadField(a, "jti");
        String jtiB = payloadField(b, "jti");
        assertNotEquals(jtiA, jtiB, "the jti is what lets us revoke one token without revoking all");
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

    private String payloadField(String jwt, String field) {
        String payload = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"")
            .matcher(payload);
        return m.find() ? m.group(1) : null;
    }
}
