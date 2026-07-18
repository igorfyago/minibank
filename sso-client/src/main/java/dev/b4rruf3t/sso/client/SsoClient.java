package dev.b4rruf3t.sso.client;

import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.function.Function;

/**
 * Validates JWTs issued by the b4rruf3t SSO service.
 * Public keys come from a JWKS source — over HTTP in production,
 * from a fixed key in tests.
 */
public final class SsoClient {
    private final String issuer;
    private final Function<String, RSAPublicKey> keyResolver;

    /** Production: resolve keys from the SSO service's JWKS endpoint. */
    public SsoClient(String issuer) {
        this(issuer, new Jwks(issuer + "/.well-known/jwks.json")::getPublicKey);
    }

    /** Test/advanced: resolve keys however you like (e.g. a fixed test key). */
    public SsoClient(String issuer, Function<String, RSAPublicKey> keyResolver) {
        this.issuer = issuer;
        this.keyResolver = keyResolver;
    }

    /**
     * Validate a JWT. Returns the user if valid, empty otherwise.
     * Checks: structure, signature, expiry, issuer, audience.
     */
    public Optional<SsoUser> validateToken(String jwt, String expectedAudience) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length != 3) return Optional.empty();

            String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]));
            String kid = extractJsonField(headerJson, "kid");
            if (kid == null) return Optional.empty();

            RSAPublicKey publicKey = keyResolver.apply(kid);
            if (publicKey == null) return Optional.empty();

            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(publicKey);
            sig.update((parts[0] + "." + parts[1]).getBytes("UTF-8"));
            if (!sig.verify(Base64.getUrlDecoder().decode(parts[2]))) {
                return Optional.empty();
            }

            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]));

            String expStr = extractJsonNumber(payloadJson, "exp");
            if (expStr == null || Instant.now().getEpochSecond() > Long.parseLong(expStr)) {
                return Optional.empty();
            }

            String iss = extractJsonField(payloadJson, "iss");
            if (!issuer.equals(iss)) return Optional.empty();

            if (expectedAudience != null && !hasAudience(payloadJson, expectedAudience)) {
                return Optional.empty();
            }

            String sub = extractJsonField(payloadJson, "sub");
            if (sub == null) return Optional.empty();
            String name = extractJsonField(payloadJson, "name");
            String email = extractJsonField(payloadJson, "email");

            return Optional.of(new SsoUser(sub, name, email));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Exact audience match: parse the aud array and compare element-wise. */
    private boolean hasAudience(String payloadJson, String expected) {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"aud\"\\s*:\\s*\\[([^\\]]*)\\]")
            .matcher(payloadJson);
        if (!m.find()) return false;
        for (String entry : m.group(1).split(",")) {
            if (entry.trim().replace("\"", "").equals(expected)) return true;
        }
        return false;
    }

    private String extractJsonField(String json, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"")
            .matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private String extractJsonNumber(String json, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"" + field + "\"\\s*:\\s*(\\d+)")
            .matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
