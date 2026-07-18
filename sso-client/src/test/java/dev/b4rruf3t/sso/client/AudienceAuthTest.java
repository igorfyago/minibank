package dev.b4rruf3t.sso.client;

import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The door lessons, generic over audience: what gets recognized, what gets
 * the same silent empty. Tokens are minted inline with a test keypair —
 * no dependency on the issuing service, because the client must stand alone.
 */
class AudienceAuthTest {

    private static final String ISSUER = "https://auth.b4rruf3t.com";
    private static final String AUD = "bank.b4rruf3t.com";

    private final RSAPublicKey publicKey;
    private final RSAPrivateKey privateKey;
    private final AudienceAuth auth;

    AudienceAuthTest() throws Exception {
        var gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        var pair = gen.generateKeyPair();
        this.publicKey = (RSAPublicKey) pair.getPublic();
        this.privateKey = (RSAPrivateKey) pair.getPrivate();
        this.auth = new AudienceAuth(ISSUER, AUD, kid -> publicKey);
    }

    @Test
    void validTokenForOurAudienceGetsIn() {
        String jwt = mint("usr_1", AUD, 900);
        Optional<SsoUser> user = auth.authenticate("Bearer " + jwt);
        assertTrue(user.isPresent());
        assertEquals("usr_1", user.get().sub());
    }

    @Test
    void missingHeaderStaysOut() {
        assertTrue(auth.authenticate(null).isEmpty());
    }

    @Test
    void nonBearerHeaderStaysOut() {
        assertTrue(auth.authenticate("Basic dXNlcjpwYXNz").isEmpty());
    }

    @Test
    void tokenForAnotherAppStaysOut() {
        // a mart token is real SSO — but it is not a bank user
        String jwt = mint("usr_1", "mart.b4rruf3t.com", 900);
        assertTrue(auth.authenticate("Bearer " + jwt).isEmpty(),
            "SSO is not all-access: each app checks its own audience");
    }

    @Test
    void garbageTokenStaysOut() {
        assertTrue(auth.authenticate("Bearer garbage").isEmpty());
        assertTrue(auth.authenticate("Bearer ").isEmpty());
    }

    @Test
    void expiredTokenStaysOut() {
        String jwt = mint("usr_1", AUD, -1);
        assertTrue(auth.authenticate("Bearer " + jwt).isEmpty());
    }

    @Test
    void everyFailureLooksIdentical() {
        // the oracle lesson: no-header, bad-token and wrong-audience must be
        // indistinguishable, or an attacker can probe which tokens exist
        assertEquals(auth.authenticate(null).isEmpty(),
                     auth.authenticate("Bearer garbage").isEmpty());
        assertEquals(auth.authenticate("Bearer garbage").isEmpty(),
                     auth.authenticate("Bearer " + mint("u", "mart.b4rruf3t.com", 900)).isEmpty());
    }

    /** Mint a token the way the SSO service does — RS256, same claims. */
    private String mint(String sub, String audience, int ttlSeconds) {
        try {
            long now = Instant.now().getEpochSecond();
            String header = b64("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"test\"}");
            String payload = b64(String.format(
                "{\"iss\":\"%s\",\"sub\":\"%s\",\"aud\":[\"%s\"],\"exp\":%d,\"iat\":%d,\"jti\":\"t\",\"name\":\"T\",\"email\":\"t@e.c\"}",
                ISSUER, sub, audience, now + ttlSeconds, now));
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(privateKey);
            sig.update((header + "." + payload).getBytes("UTF-8"));
            return header + "." + payload + "." +
                Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes());
    }
}
