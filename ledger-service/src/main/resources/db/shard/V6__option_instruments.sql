-- V6 · THE CONTRACT MULTIPLIER, and expiry as a state rather than a surprise.
--
-- WHY THE MULTIPLIER IS HERE, in the ledger, and not only in the broker.
--
-- One option contract controls 100 shares, so every money number a screen
-- shows for it is qty * price * 100. The broker already had a `multiplier`
-- column on `instruments` (V1, "100 for an option contract") and NOTHING has
-- ever read it · it is a stub, every row carries the default 1, and dropping
-- it would have changed no behaviour. A multiplier that is written and never
-- read is worse than no multiplier at all: it reads like support for options
-- that does not exist.
--
-- It cannot live only on the broker's side. HttpApi's account overview values
-- the customer's holdings INSIDE the ledger, on a shard connection, by walking
-- this registry and calling PriceFeed itself. The broker's database is a
-- different server (:5433 against the shards' :5434/:5435) and the ledger
-- cannot read it · that boundary is the point of database-per-service. So
-- either the multiplier is duplicated here, or the bank's own portfolio total
-- is wrong by a factor of a hundred for every option anybody holds.
--
-- This is denormalisation across a service boundary and it is worth naming as
-- such. The mitigation is that Catalog.list() writes BOTH halves in one call,
-- the same way it already refuses to make a symbol routable before it is
-- settleable · an instrument present in only one of them is exactly the
-- asymmetry that caused the settle-into-the-wrong-account bug V4 closed.
--
-- THE QUANTITY LEG IS NEVER MULTIPLIED, and that is load-bearing.
-- Reconciliation asserts `ledger asset balance == broker position qty - in
-- flight qty`. The ledger therefore holds CONTRACTS, not the notional shares
-- they control. Applying the multiplier to units instead of to money would
-- report a 100x divergence on every option position.

ALTER TABLE asset_slots
    ADD COLUMN IF NOT EXISTS multiplier NUMERIC(20,8) NOT NULL DEFAULT 1;

-- A zero or negative multiplier prices every holding of the instrument at
-- zero or backwards. DEFAULT 1 is the honest value for a share: one share is
-- one share, so a stock is not a special case of anything, it is the ordinary
-- case with the ordinary multiplier.
ALTER TABLE asset_slots
    DROP CONSTRAINT IF EXISTS asset_slots_multiplier_positive;
ALTER TABLE asset_slots
    ADD CONSTRAINT asset_slots_multiplier_positive CHECK (multiplier > 0);

-- The instrument's own kind, duplicated from the broker's catalog for the same
-- reason the multiplier is. This is what lets a rule be stated about options
-- as a class ("contracts are integral", "an expired one is not a live
-- position") rather than about a magic number. A multiplier on its own is a
-- bare number with no policy attached to it.
ALTER TABLE asset_slots
    ADD COLUMN IF NOT EXISTS kind TEXT NOT NULL DEFAULT 'equity';

-- EXPIRY IS DATA, and NULL means "does not expire" rather than "unknown".
--
-- An expired contract is NOT simply worth zero. It is worth its settlement
-- value, which is a number we did not observe, and writing zero would be
-- exactly the fabrication PriceFeed stopped committing when it deleted its
-- fallback constants. Zero is a price, and a wrong one.
--
-- What expiry buys is the ability to TELL TWO 404s APART. Yahoo answers the
-- same 404 for an expired contract, a strike that never existed and a typo:
--
--     AAPL250117C00250000 (expired)      -> HTTP 404
--     AAPL260821C00900000 (no such strike) -> HTTP 404
--
-- and PriceFeed's upstream-down branch relabels the last in-process price as
-- 'cached' with no age bound at all. For an equity that is defensible, because
-- the instrument still exists and the mark was real once. For an expired
-- option it is not: the contract is GONE, and the bank would render its last
-- traded premium as a current holding for as long as the process lived.
-- Without this column the feed cannot distinguish those cases, and the safe
-- reading of an ambiguous 404 is "unpriced", never "cached".
ALTER TABLE asset_slots
    ADD COLUMN IF NOT EXISTS expires_on DATE;

-- Only a dated instrument may carry an expiry. A crypto or an equity with an
-- expiry date is a contradiction, and it would silently retire a holding that
-- nothing should ever retire.
ALTER TABLE asset_slots
    DROP CONSTRAINT IF EXISTS asset_slots_expiry_only_dated;
ALTER TABLE asset_slots
    ADD CONSTRAINT asset_slots_expiry_only_dated CHECK (
        expires_on IS NULL OR kind = 'option'
    );

-- The two that predate this migration are ordinary spot instruments: one share
-- is one share, one bitcoin is one bitcoin, and neither of them expires.
UPDATE asset_slots SET multiplier = 1, kind = 'crypto'  WHERE symbol = 'BTC';
UPDATE asset_slots SET multiplier = 1, kind = 'equity'  WHERE symbol = 'AAPL';
