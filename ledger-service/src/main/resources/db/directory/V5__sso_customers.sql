-- WHO A TOKEN IS, IN THIS BANK'S OWN WORDS.
--
-- The estate's SSO at auth.b4rruf3t.com hands out a subject: an opaque,
-- stable, never-reused string that identifies a human across every app on
-- the estate. It is emphatically NOT a customer id. The bank's customer id
-- is 10 for igor because 10 is where the accounts, the entries and the
-- shard routing all point, and it was minted years before anyone signed in.
--
-- WHY THIS IS ITS OWN TABLE RATHER THAN A COLUMN ON customers.
-- A column would say "a customer has an SSO subject", which quietly makes
-- identity an attribute of the account. It is the other way round: a human
-- has accounts. The estate will eventually want one subject to reach a bank
-- customer AND a mart customer, and the shop's directory is not this one.
-- A join table is the shape that survives that; a nullable column on
-- customers is the shape that has to be migrated away from.
--
-- It also keeps the permissive rollout honest. Every existing customer has
-- no row here, which is the correct statement of the current world: the
-- demo cast is anonymous, nobody is identified, and nothing about their
-- routing or their money changes because this table exists. A nullable
-- column would have said the same thing in a way that invites a NOT NULL
-- backfill nobody can actually write.
--
-- WHY BOTH SIDES ARE UNIQUE.
-- sso_sub PRIMARY KEY: one subject resolves to one customer, or the lookup
-- is an ambiguity and the service would have to pick, which is the same as
-- guessing whose money to show.
-- customer_id UNIQUE: one customer is reachable by one subject. Two
-- subjects on one account means two humans share a login trail, and the
-- audit question "who moved this money" stops having an answer.
--
-- There is deliberately NO foreign key to customers. The link is written by
-- signup, which registers the directory row and the SSO row as separate
-- facts; a constraint between them would order those writes and buy nothing
-- a UNIQUE index does not already give. Compare V3, which dropped the token
-- FK on card_authorizations for the same reason: recording what happened
-- must not depend on the other table having caught up.

CREATE TABLE IF NOT EXISTS sso_customers (
    -- the `sub` claim, exactly as the issuer minted it · opaque on purpose,
    -- never parsed, never displayed, only ever compared
    sso_sub     TEXT PRIMARY KEY,
    -- the bank's own id for the human · the thing every other table means
    customer_id BIGINT      NOT NULL UNIQUE,
    linked_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The reverse question ("is this customer already linked?") is asked by the
-- signup path before it mints a link, and by any future account-settings
-- page. UNIQUE above already indexes it; this is the covering index for the
-- ordering the admin view wants, newest link first.
CREATE INDEX IF NOT EXISTS sso_customers_recent ON sso_customers (linked_at DESC);
