-- MINICREDIT · cycles, interest and limits are LEDGER facts, so the only
-- schema this needs is a per-row floor, an income account, an audit table
-- and an index. No cycle table: every cycle figure is a fold over entries.

-- 1. per-row floor replaces the kind-branch CHECK; a limit change becomes one UPDATE
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS min_balance NUMERIC(20,8) DEFAULT 0;
UPDATE accounts SET min_balance = CASE kind
    WHEN 'external' THEN NULL
    WHEN 'credit'   THEN -1000
    WHEN 'loan'     THEN -100000
    ELSE 0 END;
ALTER TABLE accounts DROP CONSTRAINT IF EXISTS accounts_balance_check;
-- ordering note: the backfill above runs first, and the old CHECK guaranteed
-- every credit row was already >= -1000, so the new constraint validates
ALTER TABLE accounts ADD CONSTRAINT accounts_balance_check
    CHECK (min_balance IS NULL OR balance >= min_balance);

-- 2. the bank's income account · id 2 is the last free reserved id (< 10);
--    interest vs late fees are told apart by transactions.kind, already recorded
INSERT INTO accounts(id, owner, balance, version, kind, currency, min_balance)
VALUES (2, 'bank income', 0, 0, 'external', 'EUR', NULL)
ON CONFLICT (id) DO NOTHING;

-- 3. limit changes are auditable facts (not money, so not entries)
CREATE TABLE IF NOT EXISTS credit_limit_events (
    id         BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts(id),
    old_floor  NUMERIC(20,8) NOT NULL,
    new_floor  NUMERIC(20,8) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS idx_limit_events_acct ON credit_limit_events(account_id, id);

-- 4. the statement query's index: entries by account and time
CREATE INDEX IF NOT EXISTS idx_entries_account_at ON entries(account_id, created_at);
