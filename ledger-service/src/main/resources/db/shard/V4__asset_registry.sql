-- V4 · THE ASSET REGISTRY: an asset account id becomes a LOOKUP, not a ternary.
--
-- THE BUG THIS CLOSES. Products.settleFill and Products.trade used to pick the
-- customer's asset account with
--
--     assetAcct = customerId + ("btc".equals(asset) ? BTC : AAPL)
--
-- so every symbol that was not bitcoin settled into the customer's APPLE
-- account. The books still summed to zero, per currency, on both shards ·
-- which is exactly what made it dangerous: a wrong holding that passes every
-- check the bank runs. The only thing standing between that ternary and a
-- real mis-credit was that the broker's catalog contained exactly two rows.
--
-- WHAT REPLACES IT. Two tables:
--
--   asset_slots     one row per LISTED instrument · its ledger currency, its
--                   slot number, the broker's own asset account and the
--                   per-currency clearing (in_transit) account.
--   asset_accounts  one row per (instrument, customer) · the customer's
--                   holding account id. Written LAZILY, on first trade: a
--                   customer who has never touched an instrument has no
--                   account for it, and the bank does not manufacture
--                   customers x instruments empty rows to pretend otherwise.
--
-- THE ID SPACE, and why nothing already in this database moves.
--
--   1 .. 9              per-shard system accounts (world, in_transit, broker,
--                       cafe, and the BTC/AAPL clearing pair)
--   10 .. 99            customers (Directory and HttpApi both enforce < 100)
--   110 .. 699          the product shelf · customerId + 100..600
--   >= 1 000 000 000    EVERYTHING THIS REGISTRY ALLOCATES
--
-- A billion is not a round number chosen for looks. It is above every id the
-- legacy scheme can ever produce (the largest is customer 99 + HOLDS = 699),
-- with nine orders of magnitude of daylight, so no future widening of the
-- customer range or the product shelf can grow into it. Within the asset
-- range an id is
--
--     ASSET_BASE (1e9) + slot * SLOT_STRIDE (1e6) + suffix
--
-- and the SUFFIX deliberately reuses the bank's existing convention rather
-- than inventing a second one: suffix 1 is the slot's broker account, suffix
-- 3 its clearing account (the same numbers as BROKER_EUR and IN_TRANSIT),
-- suffixes >= 10 are customer ids. That is not decoration · it means a
-- slot's clearing account always sorts BELOW its holding accounts, so the
-- ascending-lock rule every transaction in this bank obeys keeps working by
-- construction inside the new range too.
--
-- BTC AND AAPL ARE SEEDED WITH THEIR LEGACY IDS. legacy_offset is non-null
-- for exactly those two, and the holding account stays customerId + 200 /
-- customerId + 300 forever. Their broker accounts stay 5 and 6, their
-- clearing accounts 8 and 9. Every account id in the live databases keeps
-- resolving to the same account it resolved to yesterday. This migration
-- moves no money and renumbers nothing; it only writes down, in a table, the
-- mapping that used to be a ternary.

CREATE TABLE IF NOT EXISTS asset_slots (
    symbol           TEXT   PRIMARY KEY,
    currency         TEXT   NOT NULL UNIQUE,
    -- the human label the account row and every screen carry · here rather
    -- than in a fourth hand-maintained symbol->name table in Java
    label            TEXT   NOT NULL,
    slot             BIGINT NOT NULL UNIQUE,
    -- non-null ONLY for the two instruments that predate this table
    legacy_offset    BIGINT,
    broker_account   BIGINT NOT NULL UNIQUE,
    clearing_account BIGINT NOT NULL UNIQUE,
    -- non-collision, enforced by the database and not by hoping: anything
    -- allocated after BTC/AAPL must live in the asset range
    CONSTRAINT asset_slots_range CHECK (
        legacy_offset IS NOT NULL
        OR (broker_account >= 1000000000 AND clearing_account >= 1000000000)
    )
);

CREATE TABLE IF NOT EXISTS asset_accounts (
    symbol      TEXT   NOT NULL REFERENCES asset_slots(symbol),
    customer_id BIGINT NOT NULL,
    account_id  BIGINT NOT NULL UNIQUE,
    PRIMARY KEY (symbol, customer_id)
);

CREATE INDEX IF NOT EXISTS idx_asset_accounts_customer ON asset_accounts(customer_id);

-- slots 0 and 1 are reserved for these two and are never derived from a
-- symbol, so a newly listed instrument can never be handed BTC's slot
INSERT INTO asset_slots(symbol, currency, label, slot, legacy_offset, broker_account, clearing_account)
VALUES ('BTC',  'BTC',  'bitcoin',     0, 200, 5, 8),
       ('AAPL', 'AAPL', 'apple stock', 1, 300, 6, 9)
ON CONFLICT (symbol) DO NOTHING;
