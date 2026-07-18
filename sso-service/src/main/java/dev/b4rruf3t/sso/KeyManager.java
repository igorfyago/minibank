package dev.b4rruf3t.sso;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * RSA keypair for JWT signing. Generated once, then persisted — because a
 * key that changes on restart is a service that logs every user out every
 * deploy, silently. The estate deserves better than discovering that in
 * production.
 *
 * SSO_KEY_FILE points at a path on a persistent volume (the deploy mounts
 * one). If the file exists, the keypair is read back. If not, a fresh
 * 2048-bit pair is generated and written. The file contains the private
 * key; treat it like a database password.
 *
 * Public key is exposed via JWKS endpoint for validation by other apps.
 */
public final class KeyManager {
    private final RSAPublicKey publicKey;
    private final RSAPrivateKey privateKey;
    private final String keyId;

    /** Fresh, in-memory keypair. Tests and the memory-mode demo use this. */
    public KeyManager() {
        this(generate());
    }

    private KeyManager(KeyPair pair) {
        this.publicKey = (RSAPublicKey) pair.getPublic();
        this.privateKey = (RSAPrivateKey) pair.getPrivate();
        // the key id is stable for a given public key: kid = first bytes of
        // the modulus hash, so a JWKS consumer can always find its key
        this.keyId = "sso-" + fingerprint(publicKey);
    }

    /** Load from file if present, generate+persist if not. */
    public static KeyManager persisted(String path) {
        java.io.File f = new java.io.File(path);
        try {
            if (f.exists()) {
                String[] parts = new String(java.nio.file.Files.readAllBytes(f.toPath())).split(":");
                KeyFactory kf = KeyFactory.getInstance("RSA");
                var pub = (RSAPublicKey) kf.generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(parts[0])));
                var priv = (RSAPrivateKey) kf.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(parts[1])));
                return new KeyManager(new KeyPair(pub, priv));
            }
            KeyPair pair = generate();
            f.getParentFile().mkdirs();
            String encoded = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())
                + ":" + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
            java.nio.file.Files.writeString(f.toPath(), encoded);
            f.setReadable(true, true);  // owner-only, like a password file
            return new KeyManager(pair);
        } catch (Exception e) {
            throw new IllegalStateException("cannot load or persist SSO keypair at " + path, e);
        }
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("cannot generate RSA keypair", e);
        }
    }

    private static String fingerprint(RSAPublicKey key) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(key.getModulus().toByteArray());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", hash[i]));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
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
