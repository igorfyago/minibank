-- V9 · the corrected minicredit repair, as its OWN migration.
--
-- V8 was edited in place AFTER production had applied it, and Flyway did what
-- Flyway exists to do: checksum mismatch, refuse to boot, and the bank
-- crash-looped. An applied migration is history, and history is append-only ·
-- the same rule this bank's own entries table enforces with a trigger. The fix
-- lands here instead, and V8 is restored byte-for-byte to what production ran.
--
-- Everything below is idempotent, so the three environment shapes all
-- converge: a fresh database (V8 then V9), production (V8-as-applied then V9),
-- and any box the boot-time code mirror in Ledger.createSchemaOn already
-- repaired (V9 finds nothing to do).

-- 1. the audit table FIRST · the backfill below consults it, and every path
--    that moves a limit writes to it, so nothing changes a limit without a
--    trace (limit changes are auditable facts · not money, so not entries)
CREATE TABLE IF NOT EXISTS credit_limit_events (
    id         BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts(id),
    old_floor  NUMERIC(20,8) NOT NULL,
    new_floor  NUMERIC(20,8) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS idx_limit_events_acct ON credit_limit_events(account_id, id);

-- 2. per-row floor replaces the kind-branch CHECK; a limit change becomes one
--    UPDATE. The backfill mirrors Ledger.createSchemaOn exactly: rows with an
--    audit history are never touched (an explicit limit change · a freeze
--    included · is authoritative forever), and the backfill writes its own
--    audit event, which also makes it fire at most once per row. External
--    floors are NULL by design (the world going negative is how money enters
--    the bank) and carry no limit to audit.
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS min_balance NUMERIC(20,8) DEFAULT 0;
UPDATE accounts SET min_balance = NULL WHERE kind = 'external' AND min_balance IS NOT NULL;
WITH repaired AS (
    UPDATE accounts a SET min_balance = -1000
    WHERE a.kind = 'credit' AND a.min_balance = 0
      AND NOT EXISTS (SELECT 1 FROM credit_limit_events ev WHERE ev.account_id = a.id)
    RETURNING a.id)
INSERT INTO credit_limit_events(account_id, old_floor, new_floor)
SELECT id, 0, -1000 FROM repaired;
WITH repaired AS (
    UPDATE accounts a SET min_balance = -100000
    WHERE a.kind = 'loan' AND a.min_balance = 0
      AND NOT EXISTS (SELECT 1 FROM credit_limit_events ev WHERE ev.account_id = a.id)
    RETURNING a.id)
INSERT INTO credit_limit_events(account_id, old_floor, new_floor)
SELECT id, 0, -100000 FROM repaired;
ALTER TABLE accounts DROP CONSTRAINT IF EXISTS accounts_balance_check;
-- ordering note: the backfill above runs first, and the old CHECK guaranteed
-- every credit row was already >= -1000, so the new constraint validates
ALTER TABLE accounts ADD CONSTRAINT accounts_balance_check
    CHECK (min_balance IS NULL OR balance >= min_balance);

-- 3. the bank's income account · id 2 is the last free reserved id (< 10);
--    interest vs late fees are told apart by transactions.kind, already
--    recorded (the kind carries the cycle it settles: interest:YYYY-MM)
INSERT INTO accounts(id, owner, balance, version, kind, currency, min_balance)
VALUES (2, 'bank income', 0, 0, 'external', 'EUR', NULL)
ON CONFLICT (id) DO NOTHING;

-- 4. the statement query's index: entries by account and time
CREATE INDEX IF NOT EXISTS idx_entries_account_at ON entries(account_id, created_at);
