# Deploying auth.b4rruf3t.com

> The identity service goes live: one login for the whole estate.

## What this is

`sso-service` issues the JWTs every other service in the estate validates.
Until it runs, the estate recognizes tokens that nothing can mint — a
doorbell with no house behind it. After it runs, "log in once, recognized
everywhere" is physically true: the estate cookie
(`Domain=.b4rruf3t.com`, HttpOnly, Secure) is the thing that carries a
session from bank to mart to desk to pay.

## What's in the box

| Piece | Where | Purpose |
|---|---|---|
| `sso-service/Dockerfile` | minibank repo | multi-stage Maven build, bare JRE run |
| `sso-service/deploy/compose-sso.fragment.yml` | minibank repo | the service definition, ready to paste |
| `sso-service/deploy/caddy-auth.fragment` | minibank repo | the reverse-proxy block, ready to paste |
| `sso-service/src/main/resources/db/migration/` | minibank repo | V1 users, V2 sessions — Flyway owns them |

## Prerequisites on the EC2 box

- `~/apps/minibank` cloned (has `sso-service/`)
- `~/apps/ai-trading-desk/deploy/` (has `docker-compose.aws.yml` + `Caddyfile`)
- DNS: an A record for `auth.b4rruf3t.com` pointing at the box's elastic IP
  (Caddy fetches the Let's Encrypt cert on first request, same as the others)

## The deploy, in four pastes

**1. The service** — paste the `sso:` block from
`sso-service/deploy/compose-sso.fragment.yml` into
`~/apps/ai-trading-desk/deploy/docker-compose.aws.yml`, next to the
`minimart:` block.

**2. The volume** — add `sso_keys:` to the `volumes:` section of the same
file. This is where the RSA keypair persists. Without it, every rebuild
changes the signing key and every user is silently logged out.

**3. The proxy** — paste the contents of
`sso-service/deploy/caddy-auth.fragment` into
`~/apps/ai-trading-desk/deploy/Caddyfile`, next to the other subdomain
blocks, and add `sso` to the caddy service's `depends_on` list in the
compose file.

**4. Up** —

```bash
cd ~/apps/ai-trading-desk/deploy
docker compose -f docker-compose.aws.yml up -d --build sso caddy
```

The service creates the `sso` database on `minibank-control` itself on
first boot (the `SSO_ADMIN_URL` bootstrap — same pattern as
`MINIMART_ADMIN_URL`), runs the Flyway migrations, generates and persists
its keypair, and starts listening.

## Verifying it worked

```bash
# from the box
curl -s https://auth.b4rruf3t.com/health
# {"status":"ok","service":"sso"}

curl -s https://auth.b4rruf3t.com/.well-known/jwks.json | head -c 100
# {"keys":[{"kty":"RSA",...

curl -s -X POST https://auth.b4rruf3t.com/v1/users \
  -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"…","name":"You"}'
# {"id":"usr_…"}

curl -s -X POST https://auth.b4rruf3t.com/v1/tokens \
  -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"…"}' -D - | grep -i set-cookie
# set-cookie: b4rruf3t_refresh=…; Domain=.b4rruf3t.com; Path=/; HttpOnly; Secure; SameSite=Lax
```

Then open https://auth.b4rruf3t.com/login in a browser — the house-style
page should come up, and signing in should redirect you back to
bank.b4rruf3t.com with the estate cookie set. From there, every other
subdomain's eco-nav bar should show your name without a second login.

## What this does NOT change

- Every app stays permissive. Recognition everywhere, enforcement
  nowhere — the activation gate is still Igor's call, and it happens
  only when each subsystem's SSO tests are green against the LIVE
  service, not the test keypairs.
- The reactor/parent pom is still claude_code's to build. When it lands,
  the Bearer-path adapters (AudienceAuth in each Java app, incl. minipay's
  resolveIdentity seam) get wired, and recognition becomes real for
  Bearer tokens too, not just API keys.

## Operational notes

- **Restarting the service does not log anyone out.** The keypair is on
  the `sso_keys` volume; refresh sessions are in Postgres.
- **The JWKS endpoint is public** and cacheable — apps fetch it on a
  5-minute TTL.
- **Memory is capped** at 96m (`JAVA_TOOL_OPTIONS`), polite on the small box.
- **Local HTTP testing can't exercise the Secure cookie path** through a
  cookie jar (correct browser/curl behavior — Secure cookies never ride
  plain HTTP). It was verified with a manual `Cookie:` header on
  localhost, and flows naturally under Caddy's HTTPS in production.
