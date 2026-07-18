package dev.b4rruf3t.sso.client;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches and caches public keys from the SSO JWKS endpoint.
 * Keys are cached for 5 minutes, then refreshed.
 */
public final class Jwks {
    private final String jwksUrl;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, CachedKey> cache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_SECONDS = 300; // 5 minutes

    public Jwks(String jwksUrl) {
        this.jwksUrl = jwksUrl;
    }

    /** Get the public key for a key ID. Returns null if not found. */
    public RSAPublicKey getPublicKey(String kid) {
        CachedKey cached = cache.get(kid);
        if (cached != null && !cached.isExpired()) {
            return cached.key;
        }
        refresh();
        cached = cache.get(kid);
        return cached != null ? cached.key : null;
    }

    /** Force refresh of all keys. */
    public void refresh() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(jwksUrl))
                .GET()
                .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return;

            String json = res.body();
            // Parse the JWKS JSON manually (no library)
            // Format: {"keys":[{"kty":"RSA","kid":"...","n":"...","e":"..."},...]}
            String[] keyEntries = json.split("\\{\"kty\":\"RSA\"");
            for (int i = 1; i < keyEntries.length; i++) {
                String entry = keyEntries[i];
                String kid = extractField(entry, "kid");
                String n = extractField(entry, "n");
                String e = extractField(entry, "e");
                if (kid != null && n != null && e != null) {
                    RSAPublicKey key = buildKey(n, e);
                    if (key != null) {
                        cache.put(kid, new CachedKey(key, Instant.now().plusSeconds(CACHE_TTL_SECONDS)));
                    }
                }
            }
        } catch (Exception e) {
            // Silently fail — validation will return empty
        }
    }

    private RSAPublicKey buildKey(String nBase64, String eBase64) {
        try {
            byte[] nBytes = Base64.getUrlDecoder().decode(nBase64);
            byte[] eBytes = Base64.getUrlDecoder().decode(eBase64);
            BigInteger modulus = new BigInteger(1, nBytes);
            BigInteger exponent = new BigInteger(1, eBytes);
            RSAPublicKeySpec spec = new RSAPublicKeySpec(modulus, exponent);
            KeyFactory factory = KeyFactory.getInstance("RSA");
            return (RSAPublicKey) factory.generatePublic(spec);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractField(String json, String field) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "\"" + field + "\"\\s*:\\s*\"([^\"]*)\"");
        java.util.regex.Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private record CachedKey(RSAPublicKey key, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
