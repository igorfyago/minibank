package dev.b4rruf3t.sso.client;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Validates JWTs issued by the b4rruf3t SSO service.
 * Fetches public keys from the JWKS endpoint, caches them locally.
 */
public final class SsoClient {
    private final String issuer;
    private final Jwks jwks;

    public SsoClient(String issuer) {
        this.issuer = issuer;
        this.jwks = new Jwks(issuer + "/.well-known/jwks.json");
    }

    /**
     * Validate a JWT. Returns the user if valid, empty otherwise.
     * Checks: signature, expiry, issuer, audience.
     */
    public Optional<SsoUser> validateToken(String jwt, String expectedAudience) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length != 3) return Optional.empty();

            // Decode header
            String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]));
            String kid = extractJsonField(headerJson, "kid");
            if (kid == null) return Optional.empty();

            // Get public key
            RSAPublicKey publicKey = jwks.getPublicKey(kid);
            if (publicKey == null) return Optional.empty();

            // Verify signature
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(publicKey);
            sig.update((parts[0] + "." + parts[1]).getBytes("UTF-8"));
            if (!sig.verify(Base64.getUrlDecoder().decode(parts[2]))) {
                return Optional.empty();
            }

            // Decode payload
            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]));

            // Check expiry
            long exp = Long.parseLong(extractJsonField(payloadJson, "exp"));
            if (Instant.now().getEpochSecond() > exp) {
                return Optional.empty();
            }

            // Check issuer
            String iss = extractJsonField(payloadJson, "iss");
            if (!issuer.equals(iss)) {
                return Optional.empty();
            }

            // Check audience
            String audJson = extractJsonArrayField(payloadJson, "aud");
            if (expectedAudience != null && !audJson.contains(expectedAudience)) {
                return Optional.empty();
            }

            // Extract user info
            String sub = extractJsonField(payloadJson, "sub");
            String name = extractJsonField(payloadJson, "name");
            String email = extractJsonField(payloadJson, "email");

            return Optional.of(new SsoUser(sub, name, email));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String extractJsonField(String json, String field) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "\"" + field + "\"\\s*:\\s*\"([^\"]*)\"");
        java.util.regex.Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private String extractJsonArrayField(String json, String field) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "\"" + field + "\"\\s*:\\s*\\[([^\\]]*)\\]");
        java.util.regex.Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : "";
    }
}
