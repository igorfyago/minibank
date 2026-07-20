package dev.b4rruf3t.sso;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * The SSO HTTP server. Raw com.sun.net.httpserver, no framework.
 * Routes:
 *   POST /v1/users           — register
 *   POST /v1/tokens          — login
 *   POST /v1/tokens/refresh  — refresh access token
 *   POST /v1/tokens/revoke   — logout
 *   GET  /v1/users/me        — get current user (requires JWT)
 *   GET  /.well-known/jwks.json — public keys
 *   GET  /health             — health check
 */
public final class SsoServer {
    private final UserDirectory users;
    private final SessionStore sessions;
    private final TokenIssuer tokens;
    private final KeyManager keys;
    private final String issuer;

    public SsoServer(UserDirectory users, SessionStore sessions,
                     TokenIssuer tokens, KeyManager keys, String issuer) {
        this.users = users;
        this.sessions = sessions;
        this.tokens = tokens;
        this.keys = keys;
        this.issuer = issuer;
    }

    public void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/v1/users", ex -> handle(ex, this::register));
        server.createContext("/v1/tokens", ex -> handle(ex, this::login));
        server.createContext("/v1/tokens/refresh", ex -> handle(ex, this::refresh));
        server.createContext("/v1/tokens/revoke", ex -> handle(ex, this::revoke));
        server.createContext("/v1/users/me", ex -> handle(ex, this::me));
        server.createContext("/.well-known/jwks.json", ex -> handle(ex, this::jwks));
        server.createContext("/health", ex -> handle(ex, this::health));
        server.createContext("/login", ex -> handle(ex, this::loginPage));
        server.createContext("/eco-nav.js", ex -> handle(ex, this::estateAsset));
        server.createContext("/estate-auth.js", ex -> handle(ex, this::estateAsset));
        server.createContext("/", ex -> handle(ex, this::root));

        server.setExecutor(null);
        server.start();
        System.out.println("SSO service listening on :" + port);
    }

    // --- handlers ---

    private Response register(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) return Response.json(405, "{\"error\":\"method not allowed\"}");
        String body = readBody(ex);
        String email = Json.str(body, "email");
        String password = Json.str(body, "password");
        String name = Json.str(body, "name");
        if (email == null || password == null) {
            return Response.json(400, "{\"error\":\"need email, password\"}");
        }
        Optional<String> userId = users.register(email, password, name);
        if (userId.isEmpty()) {
            return Response.json(409, "{\"error\":\"email already registered\"}");
        }
        return Response.json(201, "{\"id\":\"" + userId.get() + "\"}");
    }

    private Response login(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) return Response.json(405, "{\"error\":\"method not allowed\"}");
        String body = readBody(ex);
        String email = Json.str(body, "email");
        String password = Json.str(body, "password");
        if (email == null || password == null) {
            return Response.json(400, "{\"error\":\"need email, password\"}");
        }
        Optional<String> userId = users.authenticate(email, password);
        if (userId.isEmpty()) {
            return Response.json(401, "{\"error\":\"invalid credentials\"}");
        }
        Optional<UserDirectory.User> user = users.findById(userId.get());
        if (user.isEmpty()) {
            return Response.json(500, "{\"error\":\"user not found after auth\"}");
        }
        String accessToken = tokens.issueAccessToken(
            userId.get(),
            List.of("bank.b4rruf3t.com", "mart.b4rruf3t.com", "desk.b4rruf3t.com", "pay.b4rruf3t.com"),
            user.get().displayName() != null ? user.get().displayName() : "",
            user.get().email(),
            900  // 15 minutes
        );
        String refreshToken = sessions.createSession(userId.get(), 30);
        // The cookie is what makes "log in once, recognized everywhere" true:
        // Domain=.b4rruf3t.com covers every app in the estate, so mart/desk/pay
        // can exchange it for a fresh access token on first visit.
        ex.getResponseHeaders().add("Set-Cookie",
            "b4rruf3t_refresh=" + refreshToken +
            "; Domain=.b4rruf3t.com; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=" + (30 * 86400));
        return Response.json(200, String.format(
            "{\"access_token\":\"%s\",\"refresh_token\":\"%s\",\"token_type\":\"Bearer\",\"expires_in\":900}",
            accessToken, refreshToken));
    }

    private Response refresh(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) return Response.json(405, "{\"error\":\"method not allowed\"}");
        // The refresh token arrives in the body (first-party JS on the same
        // subdomain) OR in the estate cookie (arriving at a new subdomain
        // with nothing in localStorage yet). Body wins; cookie is the fallback.
        String body = readBody(ex);
        String refreshToken = Json.str(body, "refresh_token");
        if (refreshToken == null) {
            refreshToken = cookieValue(ex, "b4rruf3t_refresh");
        }
        if (refreshToken == null) {
            return Response.json(400, "{\"error\":\"need refresh_token\"}");
        }
        Optional<String> userId = sessions.validate(refreshToken);
        if (userId.isEmpty()) {
            return Response.json(401, "{\"error\":\"invalid or expired refresh token\"}");
        }
        // Rotate: revoke old, create new
        sessions.revoke(refreshToken);
        String newRefreshToken = sessions.createSession(userId.get(), 30);
        ex.getResponseHeaders().add("Set-Cookie",
            "b4rruf3t_refresh=" + newRefreshToken +
            "; Domain=.b4rruf3t.com; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=" + (30 * 86400));
        Optional<UserDirectory.User> user = users.findById(userId.get());
        if (user.isEmpty()) {
            return Response.json(500, "{\"error\":\"user not found\"}");
        }
        String accessToken = tokens.issueAccessToken(
            userId.get(),
            List.of("bank.b4rruf3t.com", "mart.b4rruf3t.com", "desk.b4rruf3t.com", "pay.b4rruf3t.com"),
            user.get().displayName() != null ? user.get().displayName() : "",
            user.get().email(),
            900
        );
        return Response.json(200, String.format(
            "{\"access_token\":\"%s\",\"refresh_token\":\"%s\",\"token_type\":\"Bearer\",\"expires_in\":900}",
            accessToken, newRefreshToken));
    }

    private Response revoke(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) return Response.json(405, "{\"error\":\"method not allowed\"}");
        String body = readBody(ex);
        String refreshToken = Json.str(body, "refresh_token");
        if (refreshToken == null) {
            refreshToken = cookieValue(ex, "b4rruf3t_refresh");
        }
        if (refreshToken != null) {
            sessions.revoke(refreshToken);
        }
        // Clear the estate cookie either way — logout must be complete
        ex.getResponseHeaders().add("Set-Cookie",
            "b4rruf3t_refresh=; Domain=.b4rruf3t.com; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=0");
        return Response.json(200, "{\"ok\":true}");
    }

    private Response me(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) return Response.json(405, "{\"error\":\"method not allowed\"}");
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return Response.json(401, "{\"error\":\"missing token\"}");
        }
        String jwt = auth.substring(7);
        // For now, we decode without validation (apps validate via JWKS).
        // In a full implementation, the SSO service would also validate its own tokens.
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) {
            return Response.json(401, "{\"error\":\"malformed token\"}");
        }
        String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String sub = Json.str(payload, "sub");
        if (sub == null) {
            return Response.json(401, "{\"error\":\"no subject in token\"}");
        }
        Optional<UserDirectory.User> user = users.findById(sub);
        if (user.isEmpty()) {
            return Response.json(404, "{\"error\":\"user not found\"}");
        }
        return Response.json(200, String.format(
            "{\"id\":\"%s\",\"email\":\"%s\",\"name\":\"%s\"}",
            user.get().id(), escapeJson(user.get().email()),
            escapeJson(user.get().displayName() != null ? user.get().displayName() : "")));
    }

    private Response jwks(HttpExchange ex) {
        if (!"GET".equals(ex.getRequestMethod())) return Response.json(405, "{\"error\":\"method not allowed\"}");
        return Response.json(200, keys.toJwksJson());
    }

    private Response health(HttpExchange ex) {
        return Response.json(200, "{\"status\":\"ok\",\"service\":\"sso\"}");
    }

    /** The login page — the front door of the whole estate. */
    private Response loginPage(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) return Response.json(405, "{\"error\":\"method not allowed\"}");
        try (var in = getClass().getResourceAsStream("/web/login.html")) {
            if (in == null) return Response.json(404, "{\"error\":\"login page not found\"}");
            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new Response(200, html, "text/html; charset=utf-8");
        }
    }

    /** Estate JS assets every app includes cross-origin (eco-nav.js, estate-auth.js).
     *  Allowlisted by exact context path; 404 if the resource is not on the classpath yet. */
    private Response estateAsset(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) return Response.json(405, "{\"error\":\"method not allowed\"}");
        String name = ex.getHttpContext().getPath().substring(1);
        try (var in = getClass().getResourceAsStream("/web/" + name)) {
            if (in == null) return Response.json(404, "{\"error\":\"not found\"}");
            String js = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Cache-Control", "max-age=300");
            return new Response(200, js, "application/javascript; charset=utf-8");
        }
    }

    /** Root: signed-in? → /v1/users/me is one fetch away. Otherwise → /login. */
    private Response root(HttpExchange ex) {
        if (!"/".equals(ex.getRequestURI().getPath())) {
            return Response.json(404, "{\"error\":\"not found\"}");
        }
        ex.getResponseHeaders().add("Location", "/login");
        return new Response(302, "", "text/plain");
    }

    /** Read one cookie from the Cookie header. Null if absent. */
    private String cookieValue(HttpExchange ex, String name) {
        String header = ex.getRequestHeaders().getFirst("Cookie");
        if (header == null) return null;
        for (String pair : header.split(";")) {
            String[] kv = pair.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) return kv[1];
        }
        return null;
    }

    // --- helpers ---

    private void handle(HttpExchange ex, Handler handler) {
        try {
            // CORS: apps on sibling subdomains call the auth API with the
            // estate cookie. Allow any b4rruf3t origin, with credentials.
            String origin = ex.getRequestHeaders().getFirst("Origin");
            if (origin != null && origin.endsWith(".b4rruf3t.com")) {
                ex.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
                ex.getResponseHeaders().set("Access-Control-Allow-Credentials", "true");
                ex.getResponseHeaders().set("Vary", "Origin");
            }
            if ("OPTIONS".equals(ex.getRequestMethod())) {
                ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
                ex.sendResponseHeaders(204, -1);
                return;
            }
            Response r = handler.handle(ex);
            ex.getResponseHeaders().set("Content-Type", r.contentType);
            ex.sendResponseHeaders(r.status, r.body.getBytes(StandardCharsets.UTF_8).length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(r.body.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            try {
                String err = "{\"error\":\"internal error\"}";
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(500, err.getBytes(StandardCharsets.UTF_8).length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(err.getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {}
        } finally {
            ex.close();
        }
    }

    private String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @FunctionalInterface
    private interface Handler {
        Response handle(HttpExchange ex) throws IOException;
    }

    private record Response(int status, String body, String contentType) {
        static Response json(int status, String body) {
            return new Response(status, body, "application/json");
        }
    }
}
