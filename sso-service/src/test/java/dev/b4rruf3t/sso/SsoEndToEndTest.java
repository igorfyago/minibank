package dev.b4rruf3t.sso;

import dev.b4rruf3t.sso.client.SsoClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;

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

        // the token validates client-side against the live JWKS endpoint
        SsoClient client = new SsoClient(BASE);
        var user = client.validateToken(accessToken, "bank.b4rruf3t.com");
        assertTrue(user.isPresent(), "a token from the live service validates via live JWKS");
        assertEquals("igor@b4rruf3t.com", user.get().email());
        assertEquals("Igor", user.get().name());

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

    private String jsonField(String json, String field) {
        var m = java.util.regex.Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
