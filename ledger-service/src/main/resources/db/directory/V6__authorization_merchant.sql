-- The cardholder's receipt: whose shop took the money.
-- An authorization that cannot name its merchant reads as fraud on a
-- statement; "minimart" is data the caller already had at authorize time.
ALTER TABLE card_authorizations ADD COLUMN IF NOT EXISTS merchant TEXT NOT NULL DEFAULT 'card';
