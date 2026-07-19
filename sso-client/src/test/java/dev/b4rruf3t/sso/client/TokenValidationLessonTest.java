package dev.b4rruf3t.sso.client;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The lessons validateToken owes, which until now it had none of.
 *
 * Four of these come from porting this class to Python for the desk. Running
 * the same token through both clients was a differential test, and they did not
 * always agree. Every disagreement is pinned here, because two services
 * deriving different identities from one signed token is the worst thing an SSO
 * system can do quietly.
 *
 * Each test signs a REAL token with a REAL keypair. Nothing is stubbed except
 * the key lookup, so a signature that should not verify genuinely does not.
 */
class TokenValidationLessonTest {

    private static final String ISSUER = "https://auth.b4rruf3t.com";
    private static final KeyPair PAIR = generateKeyPair();
    private static final String KID = "test-key-1";

    private SsoClient client() {
        return new SsoClient(ISSUER, kid -> KID.equals(kid) ? (RSAPublicKey) PAIR.getPublic() : null);
    }

    // ------------------------------------------------------------ happy path

    @Test
    void aGenuineTokenIdentifiesItsSubject() {
        String jwt = sign(header(KID, "RS256"), claims("usr_igor", ISSUER, future(), "\"desk\""));
        var user = client().validateToken(jwt, "desk");
        assertTrue(user.isPresent(), "a correctly signed, unexpired token must validate");
        assertEquals("usr_igor", user.get().sub());
    }

    @Test
    void anExpiredTokenIsRefused() {
        String jwt = sign(header(KID, "RS256"), claims("usr_igor", ISSUER, past(), "\"desk\""));
        assertTrue(client().validateToken(jwt, "desk").isEmpty());
    }

    @Test
    void aTokenFromAnotherIssuerIsRefused() {
        String jwt = sign(header(KID, "RS256"), claims("usr_igor", "https://evil.example", future(), "\"desk\""));
        assertTrue(client().validateToken(jwt, "desk").isEmpty());
    }

    @Test
    void aSignatureFromTheWrongKeyIsRefused() throws Exception {
        KeyPair other = generateKeyPair();
        String signingInput = header(KID, "RS256") + "." + claims("usr_igor", ISSUER, future(), "\"desk\"");
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(other.getPrivate());
        s.update(signingInput.getBytes(StandardCharsets.UTF_8));
        String jwt = signingInput + "." + b64(s.sign());
        assertTrue(client().validateToken(jwt, "desk").isEmpty(), "another key's signature must not pass");
    }

    @Test
    void anUnknownKeyIdIsRefused() {
        String jwt = sign(header("no-such-kid", "RS256"), claims("usr_igor", ISSUER, future(), "\"desk\""));
        assertTrue(client().validateToken(jwt, "desk").isEmpty());
    }

    // ------------------------------------------------------------- DEFECT 1
    // alg was never read, so the client was protected only incidentally by
    // hardcoding SHA256withRSA. A token announcing "none" sailed through as
    // long as it carried a genuine RS256 signature.

    @Test
    void aTokenClaimingAlgNoneIsRefusedEvenWithAValidSignature() {
        String jwt = sign(header(KID, "none"), claims("usr_igor", ISSUER, future(), "\"desk\""));
        assertTrue(client().validateToken(jwt, "desk").isEmpty(),
                   "alg none must be refused even though the signature verifies");
    }

    @Test
    void aTokenClaimingHs256IsRefused() {
        // The RS256-to-HS256 confusion attack begins by announcing a symmetric
        // algorithm. This issuer signs one way and the client accepts one way.
        String jwt = sign(header(KID, "HS256"), claims("usr_igor", ISSUER, future(), "\"desk\""));
        assertTrue(client().validateToken(jwt, "desk").isEmpty());
    }

    @Test
    void aHeaderWithNoAlgIsRefused() {
        String jwt = sign(b64("{\"kid\":\"" + KID + "\"}"),
                          claims("usr_igor", ISSUER, future(), "\"desk\""));
        assertTrue(client().validateToken(jwt, "desk").isEmpty());
    }

    // ------------------------------------------------------------- DEFECT 2
    // The serious one. Claims were read by first-match regex; Python's
    // json.loads keeps the LAST duplicate. One signed token, two identities.

    @Test
    void aDuplicatedSubjectIsRefusedRatherThanPickingAWinner() {
        String payload = "{\"sub\":\"usr_nobody\",\"sub\":\"usr_admin\",\"iss\":\"" + ISSUER
                       + "\",\"exp\":" + future() + ",\"aud\":[\"desk\"]}";
        String jwt = sign(header(KID, "RS256"), b64(payload));

        // The old client answered usr_nobody here and the Python client
        // answered usr_admin. Refusing is the only answer both can agree on.
        assertTrue(client().validateToken(jwt, "desk").isEmpty(),
                   "a payload with two subjects must be refused, not resolved");
    }

    @Test
    void aSubjectContainingAQuoteCannotSmuggleASecondSubject() {
        // Properly escaped, so the issuer would sign it happily. Against the
        // old regex the smuggled "sub" was found first and became the identity.
        String smuggled = "x\\\",\\\"sub\\\":\\\"usr_admin";
        String payload = "{\"sub\":\"" + smuggled + "\",\"iss\":\"" + ISSUER
                       + "\",\"exp\":" + future() + ",\"aud\":[\"desk\"]}";
        String jwt = sign(header(KID, "RS256"), b64(payload));

        var user = client().validateToken(jwt, "desk");
        assertTrue(user.isPresent(), "it is a well-formed token, so it validates");
        assertEquals("x\",\"sub\":\"usr_admin", user.get().sub(),
                     "the subject is the whole literal string, not a smuggled claim");
        assertNotEquals("usr_admin", user.get().sub());
    }

    @Test
    void aDuplicatedIssuerIsAlsoRefused() {
        String payload = "{\"sub\":\"usr_igor\",\"iss\":\"https://evil.example\",\"iss\":\""
                       + ISSUER + "\",\"exp\":" + future() + ",\"aud\":[\"desk\"]}";
        String jwt = sign(header(KID, "RS256"), b64(payload));
        assertTrue(client().validateToken(jwt, "desk").isEmpty());
    }

    // ------------------------------------------------------------- audience

    @Test
    void audienceMustMatchExactlyAndNotByPrefix() {
        String jwt = sign(header(KID, "RS256"), claims("usr_igor", ISSUER, future(), "\"desk-admin\""));
        assertTrue(client().validateToken(jwt, "desk").isEmpty(),
                   "desk must not match desk-admin");
    }

    @Test
    void audienceMayBeAPlainStringPerRfc7519() {
        String payload = "{\"sub\":\"usr_igor\",\"iss\":\"" + ISSUER + "\",\"exp\":" + future()
                       + ",\"aud\":\"desk\"}";
        String jwt = sign(header(KID, "RS256"), b64(payload));
        assertTrue(client().validateToken(jwt, "desk").isPresent());
    }

    // --------------------------------------------------------------- shape

    @Test
    void structurallyBrokenInputIsRefusedWithoutThrowing() {
        SsoClient c = client();
        assertTrue(c.validateToken(null, "desk").isEmpty());
        assertTrue(c.validateToken("", "desk").isEmpty());
        assertTrue(c.validateToken("only.two", "desk").isEmpty());
        assertTrue(c.validateToken("a.b.c.d", "desk").isEmpty());
        assertTrue(c.validateToken("!!!.???.***", "desk").isEmpty());
        assertTrue(c.validateToken(b64("not json") + "." + b64("{}") + ".x", "desk").isEmpty());
    }

    @Test
    void aTokenWithNoSubjectIsRefused() {
        String payload = "{\"iss\":\"" + ISSUER + "\",\"exp\":" + future() + ",\"aud\":[\"desk\"]}";
        assertTrue(client().validateToken(sign(header(KID, "RS256"), b64(payload)), "desk").isEmpty());
    }

    // ---------------------------------------------------------------- utils

    private static String header(String kid, String alg) {
        return b64("{\"alg\":\"" + alg + "\",\"typ\":\"JWT\",\"kid\":\"" + kid + "\"}");
    }

    private static String claims(String sub, String iss, long exp, String audJsonElement) {
        return b64("{\"sub\":\"" + sub + "\",\"iss\":\"" + iss + "\",\"exp\":" + exp
                 + ",\"aud\":[" + audJsonElement + "]}");
    }

    private static long future() { return Instant.now().getEpochSecond() + 3600; }
    private static long past()   { return Instant.now().getEpochSecond() - 3600; }

    /** Sign with the real key, so a token that should not verify does not. */
    private static String sign(String headerB64, String payloadB64) {
        try {
            String input = headerB64 + "." + payloadB64;
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign(PAIR.getPrivate());
            s.update(input.getBytes(StandardCharsets.UTF_8));
            return input + "." + b64(s.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

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
}
