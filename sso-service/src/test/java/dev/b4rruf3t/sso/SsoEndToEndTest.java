package dev.b4rruf3t.sso;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The whole stack, over real HTTP, against the in-memory directory:
 * register → login → cookie → refresh → me → JWKS → client-side validation.
 * If this passes, the service works the way the estate will use it.
 */
class SsoEndToEndTest {

    private static final int PORT = 18099;
    private static final String BASE = "http://localhost:" + PORT;
    private static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void boot() throws Exception {
        MemoryDb db = new MemoryDb();
        KeyManager keys = new KeyManager();
        SsoServer server = new SsoServer(
            new UserDirectory(db), new SessionStore(db),
            new TokenIssuer(keys, BASE), keys, BASE);
        server.start(PORT);
        // give the listener a beat to bind
        Thread.sleep(300);
    }

    @Test
    void theWholeFlowWorksOverHttp() throws Exception {
        // health
        var health = http.send(HttpRequest.newBuilder(URI.create(BASE + "/health")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, health.statusCode());
        assertTrue(health.body().contains("\"ok\""));

        // register
        var reg = post("/v1/users", "{\"email\":\"igor@b4rruf3t.com\",\"password\":\"s3cret\",\"name\":\"Igor\"}");
        assertEquals(201, reg.statusCode(), "registration should create the user");

        // duplicate register is a conflict
        var dup = post("/v1/users", "{\"email\":\"igor@b4rruf3t.com\",\"password\":\"x\",\"name\":\"X\"}");
        assertEquals(409, dup.statusCode(), "the same email twice is a conflict");

        // wrong password is a 401
        var badLogin = post("/v1/tokens", "{\"email\":\"igor@b4rruf3t.com\",\"password\":\"wrong\"}");
        assertEquals(401, badLogin.statusCode());

        // login
        var login = post("/v1/tokens", "{\"email\":\"igor@b4rruf3t.com\",\"password\":\"s3cret\"}");
        assertEquals(200, login.statusCode(), "the right password gets tokens");
        String accessToken = jsonField(login.body(), "access_token");
        String refreshToken = jsonField(login.body(), "refresh_token");
        assertNotNull(accessToken);
        assertNotNull(refreshToken);

        // the estate cookie rides along
        var setCookie = login.headers().firstValue("Set-Cookie");
        assertTrue(setCookie.isPresent(), "login sets the estate cookie");
        assertTrue(setCookie.get().contains("Domain=.b4rruf3t.com"),
            "the cookie covers every subdomain — that is the whole point");
        assertTrue(setCookie.get().contains("HttpOnly"));

        // the token validates against the live JWKS endpoint, the way any
        // third party (an app, a script, curl + a JWT debugger) would prove
        // it: fetch the public key, verify the signature with java.security.
        // This module does not depend on sso-client — the dependency points
        // one way, and the test respects it.
        RSAPublicKey publicKey = fetchPublicKeyFromJwks();
        String[] parts = accessToken.split("\\.");
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initVerify(publicKey);
        sig.update((parts[0] + "." + parts[1]).getBytes("UTF-8"));
        assertTrue(sig.verify(Base64.getUrlDecoder().decode(parts[2])),
            "a token from the live service verifies against the live JWKS");

        String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
        assertTrue(payload.contains("\"sub\":\"usr_"), "the token names its user");
        assertTrue(payload.contains("bank.b4rruf3t.com"), "the token names its audiences");

        // /v1/users/me with the bearer token
        var me = http.send(HttpRequest.newBuilder(URI.create(BASE + "/v1/users/me"))
                .header("Authorization", "Bearer " + accessToken).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, me.statusCode());
        assertTrue(me.body().contains("igor@b4rruf3t.com"));

        // refresh via the body path (same-subdomain JS)
        var refreshed = post("/v1/tokens/refresh", "{\"refresh_token\":\"" + refreshToken + "\"}");
        assertEquals(200, refreshed.statusCode());
        String newRefresh = jsonField(refreshed.body(), "refresh_token");
        assertNotNull(newRefresh);
        assertNotEquals(refreshToken, newRefresh, "refresh rotates the token");

        // the old refresh token is dead (rotation revokes it)
        var stale = post("/v1/tokens/refresh", "{\"refresh_token\":\"" + refreshToken + "\"}");
        assertEquals(401, stale.statusCode(), "a rotated-out token must not work twice");

        // refresh via the cookie path (new subdomain, no localStorage)
        var cookieRefresh = http.send(HttpRequest.newBuilder(URI.create(BASE + "/v1/tokens/refresh"))
                .header("Cookie", "b4rruf3t_refresh=" + newRefresh)
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, cookieRefresh.statusCode(),
            "the cookie path is what greets you when you arrive at mart from bank");

        // whoami answers the COOKIE's question: who signed in here last.
        // The cookie just rotated, so the live one is on the refresh reply.
        String liveRefresh = jsonField(cookieRefresh.body(), "refresh_token");
        var who = http.send(HttpRequest.newBuilder(URI.create(BASE + "/v1/whoami"))
                .header("Cookie", "b4rruf3t_refresh=" + liveRefresh).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, who.statusCode(), "the estate cookie names its user");
        assertTrue(who.body().contains("igor@b4rruf3t.com"));
        assertTrue(who.body().contains("\"sub\":\"usr_"), "whoami hands back the subject apps link from");

        // no cookie and a dead cookie are the same answer: not signed in
        var nobody = http.send(HttpRequest.newBuilder(URI.create(BASE + "/v1/whoami")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(401, nobody.statusCode());
        var staleWho = http.send(HttpRequest.newBuilder(URI.create(BASE + "/v1/whoami"))
                .header("Cookie", "b4rruf3t_refresh=" + refreshToken).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(401, staleWho.statusCode(), "a rotated-out cookie must not name a user");

        // logout clears the cookie
        var logout = post("/v1/tokens/revoke", "{\"refresh_token\":\"" + jsonField(cookieRefresh.body(), "refresh_token") + "\"}");
        assertEquals(200, logout.statusCode());
        var clearCookie = logout.headers().firstValue("Set-Cookie");
        assertTrue(clearCookie.isPresent() && clearCookie.get().contains("Max-Age=0"),
            "logout clears the estate cookie");

        // the login page serves
        var page = http.send(HttpRequest.newBuilder(URI.create(BASE + "/login")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, page.statusCode());
        assertTrue(page.headers().firstValue("Content-Type").orElse("").contains("text/html"));
        assertTrue(page.body().contains("b4rruf"), "the front door looks like the house");

        // JWKS is public
        var jwks = http.send(HttpRequest.newBuilder(URI.create(BASE + "/.well-known/jwks.json")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, jwks.statusCode());
        assertTrue(jwks.body().contains("\"kty\":\"RSA\""));
    }

    @Test
    void unauthenticatedMeIs401() throws Exception {
        var me = http.send(HttpRequest.newBuilder(URI.create(BASE + "/v1/users/me")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(401, me.statusCode(), "no token, no identity");
    }

    @Test
    void corsAnswersPreflightForEstateOrigins() throws Exception {
        var preflight = http.send(HttpRequest.newBuilder(URI.create(BASE + "/v1/tokens"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "https://mart.b4rruf3t.com").build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(204, preflight.statusCode());
        assertEquals("https://mart.b4rruf3t.com",
            preflight.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
        assertEquals("true",
            preflight.headers().firstValue("Access-Control-Allow-Credentials").orElse(""));
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    /** Fetch and rebuild the RSA public key from the live JWKS endpoint —
     *  the exact steps any third party takes, with no client library. */
    private RSAPublicKey fetchPublicKeyFromJwks() throws Exception {
        var res = http.send(HttpRequest.newBuilder(URI.create(BASE + "/.well-known/jwks.json")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), "the JWKS endpoint is public");
        String n = jsonField(res.body(), "n");
        String e = jsonField(res.body(), "e");
        var spec = new RSAPublicKeySpec(
            new BigInteger(1, Base64.getUrlDecoder().decode(n)),
            new BigInteger(1, Base64.getUrlDecoder().decode(e)));
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
    }

    private String jsonField(String json, String field) {
        var m = java.util.regex.Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
