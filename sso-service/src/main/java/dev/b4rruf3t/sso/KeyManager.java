package dev.b4rruf3t.sso;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

/**
 * RSA keypair for JWT signing. One active keypair, rotated on restart.
 * Public key exposed via JWKS endpoint for validation by other apps.
 */
public final class KeyManager {
    private final RSAPublicKey publicKey;
    private final RSAPrivateKey privateKey;
    private final String keyId;

    public KeyManager() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();
            this.publicKey = (RSAPublicKey) pair.getPublic();
            this.privateKey = (RSAPrivateKey) pair.getPrivate();
            this.keyId = "sso-" + System.currentTimeMillis();
        } catch (Exception e) {
            throw new IllegalStateException("cannot generate RSA keypair", e);
        }
    }

    public RSAPublicKey publicKey() { return publicKey; }
    public RSAPrivateKey privateKey() { return privateKey; }
    public String keyId() { return keyId; }

    /** JWKS format: one key entry per RFC 7517 */
    public String toJwksJson() {
        String n = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(publicKey.getModulus().toByteArray());
        String e = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(publicKey.getPublicExponent().toByteArray());
        return String.format(
            "{\"keys\":[{\"kty\":\"RSA\",\"use\":\"sig\",\"kid\":\"%s\",\"n\":\"%s\",\"e\":\"%s\",\"alg\":\"RS256\"}]}",
            keyId, n, e);
    }
}
