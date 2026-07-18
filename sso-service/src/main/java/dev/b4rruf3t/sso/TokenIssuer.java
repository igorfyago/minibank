package dev.b4rruf3t.sso;

import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Issues JWTs signed with RS256. No external JWT library — we build
 * the token by hand to keep the "no frameworks" doctrine.
 *
 * A JWT is three base64url parts joined by dots:
 *   header.payload.signature
 *
 * The signature covers header + "." + payload.
 */
public final class TokenIssuer {
    private final KeyManager keys;
    private final String issuer;

    public TokenIssuer(KeyManager keys, String issuer) {
        this.keys = keys;
        this.issuer = issuer;
    }

    /**
     * Issue an access token.
     *
     * @param subject    the user ID (usr_...)
     * @param audiences  which apps this token is valid for
     * @param name       display name
     * @param email      user email
     * @param ttlSeconds how long the token lives (default 900 = 15 min)
     */
    public String issueAccessToken(String subject, List<String> audiences,
                                   String name, String email, int ttlSeconds) {
        long now = Instant.now().getEpochSecond();
        String header = base64url("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"" + keys.keyId() + "\"}");
        String payload = base64url(String.format(
            "{\"iss\":\"%s\",\"sub\":\"%s\",\"aud\":%s,\"exp\":%d,\"iat\":%d,\"jti\":\"tok_%s\",\"name\":\"%s\",\"email\":\"%s\"}",
            issuer, subject, toJsonArray(audiences), now + ttlSeconds, now,
            generateId(), escapeJson(name), escapeJson(email)));

        String signature = sign(header + "." + payload);
        return header + "." + payload + "." + signature;
    }

    private String sign(String data) {
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(keys.privateKey());
            sig.update(data.getBytes("UTF-8"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());
        } catch (Exception e) {
            throw new IllegalStateException("cannot sign JWT", e);
        }
    }

    private String base64url(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes());
    }

    private String toJsonArray(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escapeJson(items.get(i))).append("\"");
        }
        return sb.append("]").toString();
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String generateId() {
        // Simple ID: timestamp + random suffix
        return Long.toHexString(System.currentTimeMillis()) +
               Long.toHexString((long) (Math.random() * Long.MAX_VALUE)).substring(0, 8);
    }
}
