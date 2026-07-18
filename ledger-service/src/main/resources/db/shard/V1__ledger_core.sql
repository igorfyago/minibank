-- V1 · the double-entry ledger core (stage 1).
-- Idempotent by design: these same statements have created every shard
-- since before Flyway existed, so on an already-live database Flyway
-- baselines and this runs as a safe no-op; on a fresh database it builds
-- the schema from nothing.

CREATE TABLE IF NOT EXISTS accounts (
    id      BIGINT PRIMARY KEY,
    owner   TEXT   NOT NULL,
    balance NUMERIC(12,2) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS transactions (
    id         UUID PRIMARY KEY,
    kind       TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS entries (
    id         BIGSERIAL PRIMARY KEY,
    tx_id      UUID   NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
    account_id BIGINT NOT NULL REFERENCES accounts(id)     ON DELETE CASCADE,
    amount     NUMERIC(12,2) NOT NULL CHECK (amount <> 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS outbox (
    id           BIGSERIAL PRIMARY KEY,
    topic        TEXT NOT NULL,
    key          TEXT NOT NULL,
    payload      TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_outbox_unpublished ON outbox(id) WHERE published_at IS NULL;
