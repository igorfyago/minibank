-- V7 · the broker's half of the option instrument.
--
-- `multiplier` already exists here. V1 created it with the comment "100 for an
-- option contract" and then nothing ever read it: Catalog.Instrument had no
-- such component, Catalog.COLUMNS did not select it, Catalog.put did not write
-- it. Every row carries the default 1 and the column could have been dropped
-- without changing a single number. From this migration on it is READ, by
-- Broker.consideration and by Portfolio, which is what the V1 comment always
-- implied it was for.
--
-- What is missing here is the expiry. The shard's asset_slots gets the same
-- column (db/shard/V6__option_instruments.sql) because the ledger values
-- holdings itself and cannot read this database; this side needs it because
-- the broker is what refuses an order on a contract that has already expired.

ALTER TABLE instruments
    ADD COLUMN IF NOT EXISTS expires_on DATE;

ALTER TABLE instruments
    DROP CONSTRAINT IF EXISTS instruments_expiry_only_dated;
ALTER TABLE instruments
    ADD CONSTRAINT instruments_expiry_only_dated CHECK (
        expires_on IS NULL OR kind = 'option'
    );

-- The V1 column had no guard. A zero multiplier prices every holding of the
-- instrument at nothing; a negative one prices it backwards.
ALTER TABLE instruments
    DROP CONSTRAINT IF EXISTS instruments_multiplier_positive;
ALTER TABLE instruments
    ADD CONSTRAINT instruments_multiplier_positive CHECK (multiplier > 0);
