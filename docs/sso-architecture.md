# b4rruf3t Ecosystem SSO Architecture

> One login, one identity, seamless navigation across the whole estate.

## The Vision

A user lands on any b4rruf3t property — bank, mart, desk, pay, gex, obs. They log in once. Every other property recognizes them instantly. They click a tab from bank to mart to desk, and their identity travels with them. No re-auth, no friction, no "please log in again."

This is the Revolut/Stripe/Amazon play: one account, many products.

## Architecture Overview

```
┌─────────────────────────────────────────┐
│         auth.b4rruf3t.com               │
│  ┌─────────┐ ┌─────────┐ ┌───────────┐ │
│  │ Register│ │  Login  │ │  JWKS     │ │
│  │ /v1/    │ │ /v1/    │ │ /.well-   │ │
│  │ users   │ │ tokens  │ │ known/    │ │
│  │         │ │         │ │ jwks.json │ │
│  └─────────┘ └─────────┘ └───────────┘ │
│  ┌─────────┐ ┌─────────┐ ┌───────────┐ │
│  │ User    │ │ Session │ │  Token    │ │
│  │ Directory│ │ Store   │ │  Issuer   │ │
│  │ (Postgres)│ │(Postgres)│ │ (RS256)  │ │
│  └─────────┘ └─────────┘ └───────────┘ │
└─────────────────────────────────────────┘
                    │
    ┌───────────────┼───────────────┐
    ▼               ▼               ▼
┌────────┐    ┌────────┐    ┌────────────┐
│minibank│    │minimart│    │ minitrade  │
│(ledger)│    │ (shop) │    │ (options)  │
│        │    │        │    │            │
│Validates│   │Validates│   │ Validates  │
│JWT via │    │JWT via │    │ JWT via    │
│sso-    │    │sso-    │    │ sso-       │
│client  │    │client  │    │ client     │
│        │    │        │    │            │
│Maps sub│    │Maps sub│    │ Maps sub   │
│→       │    │→       │    │ →          │
│customer│    │customer│    │ trader     │
│_id     │    │_id     │    │ _id        │
└────────┘    └────────┘    └────────────┘
    │               │               │
    └───────────────┴───────────────┘
                    │
            ┌────────┐
            │ minipay │
            │(Stripe  │
            │  rail)  │
            │        │
            │Validates│
            │JWT via │
            │sso-    │
            │client  │
            │        │
            │Maps sub│
            │→       │
            │merchant│
            │or NPC  │
            │_id     │
            └────────┘
```

## Core Principles

1. **The SSO service owns identity, not user data.** It knows who you are (subject ID), not what you do (your accounts, orders, positions). Each app keeps its own user table.

2. **JWT is the passport.** Signed with RS256 (private key at SSO, public keys at JWKS endpoint). Every app validates the same JWT with the same public key.

3. **Subject ID (`sub`) is the universal key.** Each app maps `sub` to its local user ID. The mapping is owned by the app, not the SSO service.

4. **No shared session store.** JWT is stateless. Apps don't need to call back to the SSO service to validate — they verify the signature locally.

5. **Token refresh is seamless.** Short-lived access tokens (15 min), long-lived refresh tokens (30 days, sliding window). Apps refresh automatically.

## The SSO Service (auth.b4rruf3t.com)

Built as a new module in the minibank repo (same raw Java 21, no frameworks style):

```
sso-service/
  src/main/java/dev/b4rruf3t/sso/
    SsoServer.java          — HTTP server, routes
    UserDirectory.java      — user CRUD, Postgres
    TokenIssuer.java        — JWT signing (RS256)
    KeyManager.java         — RSA keypair, rotation
    SessionStore.java       — refresh token persistence
    Json.java               — JSON parsing (copied from minibank)
    Db.java                 — connection pool (copied from minibank)
    Migrate.java            — Flyway migrations
  src/main/resources/
    db/migration/           — V1__users.sql, V2__sessions.sql
    web/                    — login/register UI
```

### Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/v1/users` | Register (email, password, display name) |
| `POST` | `/v1/tokens` | Login (email, password) → access + refresh token |
| `POST` | `/v1/tokens/refresh` | Refresh access token |
| `POST` | `/v1/tokens/revoke` | Logout (revoke refresh token) |
| `GET` | `/v1/users/me` | Get current user profile (requires valid JWT) |
| `GET` | `/.well-known/jwks.json` | Public keys for JWT validation |
| `GET` | `/health` | Health check |

### JWT Structure

```json
{
  "iss": "https://auth.b4rruf3t.com",
  "sub": "usr_01JZKX8V2Q3M4N5P6R7S8T9",
  "aud": ["bank.b4rruf3t.com", "mart.b4rruf3t.com", "desk.b4rruf3t.com", "pay.b4rruf3t.com"],
  "exp": 1721300400,
  "iat": 1721299500,
  "jti": "tok_01JZKX8V2Q3M4N5P6R7S8T9",
  "name": "Igor",
  "email": "igor@b4rruf3t.com"
}
```

### Database Schema

```sql
-- V1__users.sql
CREATE TABLE users (
    id TEXT PRIMARY KEY,                    -- usr_01JZKX8V2Q3M4N5P6R7S8T9
    email TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,            -- bcrypt
    display_name TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- V2__sessions.sql (refresh tokens)
CREATE TABLE sessions (
    id TEXT PRIMARY KEY,                    -- ses_01JZKX8V2Q3M4N5P6R7S8T9
    user_id TEXT REFERENCES users(id),
    refresh_token_hash TEXT UNIQUE NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    revoked_at TIMESTAMPTZ
);

CREATE INDEX idx_sessions_user ON sessions(user_id);
CREATE INDEX idx_sessions_expires ON sessions(expires_at);
```

## The Shared Client Library (sso-client)

A small Java library that any app can depend on. Published as a Maven module in the minibank repo.

```java
// Usage in any app
SsoClient sso = new SsoClient("https://auth.b4rruf3t.com");

// Validate a JWT
Optional<SsoUser> user = sso.validateToken(jwt);

// Get public keys (cached, auto-refreshed)
Jwks jwks = sso.getJwks();
```

### Features

- JWT validation (signature, expiry, issuer, audience)
- JWKS fetching with local cache (5 min TTL)
- Automatic retry on network failure
- No external dependencies beyond JDK + JSON

### Maven Coordinates

```xml
<dependency>
    <groupId>dev.b4rruf3t</groupId>
    <artifactId>sso-client</artifactId>
    <version>1.0.0</version>
</dependency>
```

## App Integration Pattern

Each app integrates SSO in three steps:

### 1. Add the sso-client dependency

```xml
<dependency>
    <groupId>dev.b4rruf3t</groupId>
    <artifactId>sso-client</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 2. Add the auth middleware

```java
// In the app's HTTP handler
SsoClient sso = new SsoClient("https://auth.b4rruf3t.com");

// Extract JWT from Authorization header
String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
if (authHeader == null || !authHeader.startsWith("Bearer ")) {
    return Response.json(401, "{\"error\":\"missing token\"}");
}

String jwt = authHeader.substring(7);
Optional<SsoUser> user = sso.validateToken(jwt);
if (user.isEmpty()) {
    return Response.json(401, "{\"error\":\"invalid token\"}");
}

// Map SSO subject to local user
String ssoSub = user.get().sub();
Long localUserId = mapSsoSubToLocalId(ssoSub);
```

### 3. Add the ecosystem navigation header

```html
<!-- Shared header, included in every app -->
<nav id="eco-nav">
  <a href="https://bank.b4rruf3t.com" class="eco-tab">Bank</a>
  <a href="https://mart.b4rruf3t.com" class="eco-tab">Shop</a>
  <a href="https://desk.b4rruf3t.com" class="eco-tab">Trade</a>
  <a href="https://pay.b4rruf3t.com" class="eco-tab">Pay</a>
  <span id="eco-user"></span>  <!-- populated by JS -->
</nav>

<script>
// Check if user is logged in
const token = localStorage.getItem('b4rruf3t_token');
if (token) {
  fetch('https://auth.b4rruf3t.com/v1/users/me', {
    headers: { 'Authorization': 'Bearer ' + token }
  }).then(r => r.json()).then(user => {
    document.getElementById('eco-user').textContent = user.name;
  });
}
</script>
```

## Token Flow

### Login

```
User → auth.b4rruf3t.com/v1/tokens
       {email, password}
           ↓
       SSO validates credentials
           ↓
       SSO issues:
         - access_token (JWT, 15 min expiry)
         - refresh_token (opaque string, 30 day expiry)
           ↓
       User stores both in localStorage
```

### API Call

```
User → any app (bank, mart, desk, pay)
       Authorization: Bearer <access_token>
           ↓
       App validates JWT locally (sso-client)
           ↓
       App maps sub → local user ID
           ↓
       App serves request
```

### Token Refresh

```
User's access token expires
           ↓
       App returns 401
           ↓
       User's JS sends refresh_token to /v1/tokens/refresh
           ↓
       SSO validates refresh token
           ↓
       SSO issues new access_token + new refresh_token
           ↓
       User stores new tokens, retries original request
```

### Cross-App Navigation

```
User is on bank.b4rruf3t.com, clicks "Shop" tab
           ↓
       Browser navigates to mart.b4rruf3t.com
           ↓
       Mart's JS checks localStorage for b4rruf3t_token
           ↓
       Token exists → mart validates it, shows user as logged in
           ↓
       User sees their minimart profile without re-logging in
```

## Security Considerations

| Threat | Mitigation |
|--------|-----------|
| Token theft via XSS | HttpOnly cookies (future), short expiry, refresh rotation |
| Token replay | `jti` claim + revocation list |
| Key compromise | RSA key rotation, JWKS cache TTL |
| Password breach | bcrypt hashing, rate limiting on login |
| CSRF | SameSite cookies, custom header for API calls |

## Rollout Plan

### Phase 1: SSO Service (this week)
- [ ] Build `sso-service` module in minibank repo
- [ ] User registration, login, JWT issuance
- [ ] JWKS endpoint
- [ ] Deploy to auth.b4rruf3t.com

### Phase 2: sso-client Library (this week)
- [ ] Build `sso-client` module
- [ ] JWT validation, JWKS caching
- [ ] Publish to local Maven repo

### Phase 3: minibank Integration (next week)
- [ ] Add sso-client to ledger-service
- [ ] Protect endpoints with JWT
- [ ] Map SSO sub → customer_id
- [ ] Add ecosystem nav header

### Phase 4: minimart Integration
- [ ] Add sso-client to minimart
- [ ] Protect endpoints with JWT
- [ ] Map SSO sub → customer_id
- [ ] Add ecosystem nav header

### Phase 5: minitrade Integration
- [ ] Add sso-client to minitrade
- [ ] Protect endpoints with JWT
- [ ] Map SSO sub → trader_id
- [ ] Add ecosystem nav header

### Phase 6: minipay Integration
- [ ] Add sso-client to minipay
- [ ] Protect endpoints with JWT
- [ ] Map SSO sub → merchant_id or NPC_id
- [ ] Add ecosystem nav header

### Phase 7: Ecosystem Polish
- [ ] Shared login UI component
- [ ] Cross-app user settings
- [ ] Unified logout (revoke all sessions)
- [ ] Audit log (who logged in, when, from where)

## The CV Winning Move

This isn't "I built 7 projects." This is:

> "I designed and built a unified identity platform for a fintech ecosystem. The SSO service issues JWTs validated by 6 independent applications. Users log in once and navigate seamlessly across banking, commerce, trading, and payments. The architecture mirrors how Revolut and Stripe unify their product suites."

That's a staff engineer / architect level story.

## Files to Build

| File | Purpose | Status |
|------|---------|--------|
| `sso-service/src/main/java/dev/b4rruf3t/sso/SsoServer.java` | HTTP server, routes | pending |
| `sso-service/src/main/java/dev/b4rruf3t/sso/UserDirectory.java` | User CRUD | pending |
| `sso-service/src/main/java/dev/b4rruf3t/sso/TokenIssuer.java` | JWT signing | pending |
| `sso-service/src/main/java/dev/b4rruf3t/sso/KeyManager.java` | RSA keypair | pending |
| `sso-service/src/main/java/dev/b4rruf3t/sso/SessionStore.java` | Refresh tokens | pending |
| `sso-service/src/main/resources/db/migration/V1__users.sql` | Users table | pending |
| `sso-service/src/main/resources/db/migration/V2__sessions.sql` | Sessions table | pending |
| `sso-client/src/main/java/dev/b4rruf3t/sso/client/SsoClient.java` | JWT validation | pending |
| `sso-client/src/main/java/dev/b4rruf3t/sso/client/Jwks.java` | JWKS fetching | pending |
| `sso-client/src/main/java/dev/b4rruf3t/sso/client/SsoUser.java` | User model | pending |
| `docs/sso-architecture.md` | This document | done |
