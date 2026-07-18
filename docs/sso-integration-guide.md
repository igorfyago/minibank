# SSO Integration Guide — every app in the estate

> One page per app: what to add, what to change, what to prove.
> The pattern is identical everywhere; only the local user mapping differs.
> minibank is the reference implementation — see `dev.minibank.auth.BankAuth`
> and `db/auth/V1__sso_customers.sql`.

## The pattern (same for every app)

1. **Depend on `sso-client`** — the zero-dependency JWT validator.
2. **One auth class** — `XxxAuth` wrapping `SsoClient` with the app's audience.
3. **One mapping table** — `sso_sub → local user id`, owned by the app.
4. **PERMISSIVE until activation** — validate the token if present and attach
   identity, but NEVER 401 a missing/invalid token yet. Enforcement flips on
   estate-wide by explicit decision once every app's integration tests pass.
   Until then a 401-on-missing-token is a bug, and the public demos
   (#show, X-ray, Quiz, storefront, chart) must work anonymously.
5. **The eco-nav header** — copy `eco-nav.html`, the tabs light up.
6. **Identity beats parameters** — when a valid token for user A arrives with
   a `?customer=B` parameter, serve A, never B. The token is the identity;
   the parameter is a hint for anonymous traffic only.

---

## minimart (mart.b4rruf3t.com)

**Audience:** `mart.b4rruf3t.com`

**Mapping:** `sso_sub → customer_id`. minimart's customers are currently seeded
agents (`(runId, agentId, tick, step)` pure functions). Real humans arrive as
SSO users and get rows in a new `sso_customers` table — the simulation and the
storefront coexist: agents drive load, humans drive the till.

```sql
CREATE TABLE sso_customers (
    sso_sub      TEXT PRIMARY KEY,
    customer_id  BIGINT NOT NULL UNIQUE,  -- references customers(id)
    linked_at    TIMESTAMPTZ DEFAULT NOW()
);
```

**Protected endpoints:** checkout, subscriptions, order history, account.
**Public endpoints:** catalogue, product pages, the simulation dashboard
(watching software shop is not behind a login — it's the demo).

**The minipay seam:** when a human checks out, minimart calls minipay with
their SSO token in the `Authorization` header. minipay validates the token
itself (audience `pay.b4rruf3t.com`) — the store never vouches for identity,
it just passes the token through. **A token for mart does not open pay** — the
user needs both audiences in their JWT, which the SSO service grants at login.

**Tests to write (permissive phase):**
- a mart token → identity attached at checkout; a bank token → not attached
- no token → checkout behaves exactly as today (permissive)
- an SSO user checking out lands a row linking `sso_sub → customer_id`
- the simulation still runs without any SSO (agents don't log in)
- token for user A + another customer's id in params → serves A, never B

---

## minitrade (desk.b4rruf3t.com)

**Audience:** `desk.b4rruf3t.com`

**Mapping:** `sso_sub → trader_id`. New table `sso_traders`. A trader row
links the SSO identity to a position book and a watchlist.

```sql
CREATE TABLE sso_traders (
    sso_sub      TEXT PRIMARY KEY,
    trader_id    BIGINT NOT NULL UNIQUE,
    linked_at    TIMESTAMPTZ DEFAULT NOW()
);
```

**Protected endpoints:** position book actions (open/close/size), watchlist
writes, anything that *acts*.
**Public endpoints:** the chart, the tape, Marcus listening, GEX levels —
reading the market is free, trading it is behind the login.

**Python integration note:** minitrade is Python, not Java — it can't use
`sso-client`. The equivalent is ~30 lines with `PyJWT` (or hand-rolled RS256
verification like the Java client — the format is the same). Fetch JWKS from
`auth.b4rruf3t.com/.well-known/jwks.json`, verify signature + exp + iss + aud.
A `sso_client.py` in `common/` mirrors the Java library's contract exactly:
same claims checked, same rejection rules, same `SsoUser` shape.

**Tests to write (permissive phase):**
- a desk token → identity attached on the position book; a mart token → not attached
- no token → position book and watchlist behave exactly as today (permissive)
- the public chart renders without any token
- token for user A + another trader's book in params → serves A, never B

---

## minipay (pay.b4rruf3t.com)

**Audience:** `pay.b4rruf3t.com`

**Two kinds of users — this is the Stripe-shaped part:**

1. **Ecosystem users** — SSO users paying for things. `sso_sub → account_id`
   in `sso_accounts`. They get an audience for pay because they bank with us.

2. **External NPC/visitor users** — no SSO account. They authenticate with an
   **API key** minipay issues (the Stripe play: `pk_live_...` / `sk_live_...`).
   API keys are their own table, their own auth path — they never get JWTs,
   they never see the other apps.

```sql
-- ecosystem users
CREATE TABLE sso_accounts (
    sso_sub      TEXT PRIMARY KEY,
    account_id   BIGINT NOT NULL UNIQUE,
    linked_at    TIMESTAMPTZ DEFAULT NOW()
);

-- external users (the Stripe role)
CREATE TABLE api_keys (
    key_id       TEXT PRIMARY KEY,        -- pk_live_... (public) or key id
    key_hash     TEXT NOT NULL,           -- sha-256 of the secret half
    owner_name   TEXT NOT NULL,           -- "Agentic Visitors Inc"
    scope        TEXT NOT NULL,           -- 'charge', 'refund', 'read'
    created_at   TIMESTAMPTZ DEFAULT NOW(),
    revoked_at   TIMESTAMPTZ
);
```

**Auth order:** check `Authorization: Bearer` first (SSO path), then
`Authorization: Basic` / `X-Api-Key` (API key path). A request must match
exactly one.

**Protected endpoints:** everything except the public status page.
**The agentic-visitors seam:** this is how LLM-driven NPCs pay. The visitors
service holds an API key, debits its own ledger, and minipay sees just another
external merchant. The containment shell's budget cap and minipay's balance
check are the same sentence: cannot overspend.

**Tests to write (permissive phase):**
- an SSO pay token → identity attached on charge; an SSO bank-only token → not attached
- no token → endpoints behave exactly as today (permissive)
- an API key attaches its merchant identity, scoped; a revoked key attaches nothing
- JWT and API key on the same request → 400 (ambiguous identity) — this one IS
  an error even in the permissive phase, because guessing would be worse

---

## The eco-nav header (all apps, including Python ones)

Copy `sso-service/src/main/resources/web/eco-nav.html` into each app's web
root and include it at the top of the body. It:

- renders the tab bar (Bank · Shop · Trade · Pay · Data · Agents)
- highlights the current app by hostname
- checks `localStorage.b4rruf3t_token`, shows the user's name + avatar
- silently refreshes expired access tokens via the refresh token
- offers Sign in / Sign out

Because the token lives in `localStorage` under the **same key on every
subdomain**... wait — it doesn't. `localStorage` is per-origin, so
`bank.b4rruf3t.com` and `mart.b4rruf3t.com` have separate storage.

**The fix — the cookie is the carrier.** The SSO service sets a
`Domain=.b4rruf3t.com` cookie (the leading dot covers all subdomains) holding
the refresh token, `HttpOnly; Secure; SameSite=Lax`. On first load, each app's
JS calls `/v1/tokens/refresh` **with the cookie** (no body needed) and gets a
fresh access token. That's the real "log in once, recognized everywhere":

```
login at bank        → cookie set on .b4rruf3t.com
navigate to mart     → mart JS finds no localStorage token
                     → calls /v1/tokens/refresh with the cookie
                     → gets an access token, user is in
```

`eco-nav.html` already contains the refresh flow — the remaining work is on
the SSO service: set the cookie at login, accept it at refresh. That change
lands with the login UI (next phase).

---

## Rollout order

1. **minibank** — reference implementation (done: BankAuth, sso_customers migration, 7/7 tests)
2. **minimart** — closest pattern match (also Java 21, same doctrine)
3. **minipay** — the API-key dimension makes it the most valuable (external NPCs)
4. **minitrade** — needs the Python `sso_client.py` port first

Each integration is independent: an app can go live with SSO while the others
haven't started. The token is the only contract.
