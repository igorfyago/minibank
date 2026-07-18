-- V2 · products + a currency per account (the products stage).
-- A kind per account, a currency per account, wider money columns, and the
-- business rule pushed INTO the schema as a kind-aware CHECK constraint:
-- customers never negative, externals free, a card to -1000, a loan to -100000.

ALTER TABLE accounts ADD COLUMN IF NOT EXISTS kind     TEXT NOT NULL DEFAULT 'customer';
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS currency TEXT NOT NULL DEFAULT 'EUR';
ALTER TABLE accounts ALTER COLUMN balance TYPE NUMERIC(20,8);
ALTER TABLE entries  ALTER COLUMN amount  TYPE NUMERIC(20,8);

ALTER TABLE accounts DROP CONSTRAINT IF EXISTS accounts_balance_check;
ALTER TABLE accounts ADD CONSTRAINT accounts_balance_check
    CHECK (kind = 'external'
        OR (kind = 'credit' AND balance >= -1000)
        OR (kind = 'loan'   AND balance >= -100000)
        OR balance >= 0);

CREATE INDEX IF NOT EXISTS idx_entries_account ON entries(account_id, id);
