-- V10 · SLOT CAPACITY: the derived range widens, and the arithmetic that maps
-- a slot onto its accounts becomes a CONSTRAINT instead of an argument.
--
-- THE BLOCKER THIS CLOSES. derivedSlot folds a symbol's hash into
-- [2, SLOT_LIMIT), and SLOT_LIMIT was one million. One listed option chain ·
-- 8 expiries x 100 strikes x calls and puts is 1,600 contracts · has an
-- expected 1.28 colliding pairs at that size, which is a ~72% chance that
-- listing a single chain refuses at least one perfectly good contract. The
-- registry fails loudly at listing time, which is the right failure, but a
-- gate that trips on most chains is not capacity, it is a cap. At 10^12 the
-- same chain's collision chance is ~1.3e-6, and the largest id the scheme can
-- now mint (ASSET_BASE + (10^12-1) * 10^6 + 99) is still an order of
-- magnitude under BIGINT's ceiling.
--
-- NOTHING RECORDED MOVES, and that is provable rather than promised:
--
--   * every read path (bySymbol, byCurrency, all) returns slot,
--     broker_account and clearing_account from THESE COLUMNS · nothing
--     re-derives them from the hash;
--   * holdingFor computes future holdings from the RECORDED slot times
--     ASSET_BASE/SLOT_STRIDE, which this migration does not touch;
--   * asset_accounts rows win over any derivation (first write wins), so an
--     id a customer already holds under is a fact, not a formula.
--
-- SLOT_LIMIT is therefore the only safe knob: it is consulted exactly once,
-- inside derivedSlot, at the moment a NEW symbol is listed. Widening it
-- changes where instruments not yet listed will land and renumbers nothing
-- that exists. ASSET_BASE and SLOT_STRIDE are NOT safe knobs · they are how a
-- recorded slot becomes future holding ids, and moving either would put new
-- holdings of an already-listed instrument in a different block from its
-- recorded broker and clearing accounts, breaking the
-- clearing-sorts-below-holdings lock-order construction.
--
-- THE CHECK. Every derived row this table has ever gained was written by
-- register(), which computed broker_account and clearing_account from the
-- slot with exactly this arithmetic · so existing rows satisfy it by
-- construction, and the migration touches no data. What the constraint buys
-- is that "a code change cannot renumber a recorded row" stops being a
-- property of the current Java and becomes a property of the database: a
-- future register() whose arithmetic drifts from its own recorded history
-- gets a constraint violation, not a quiet second numbering scheme.

ALTER TABLE asset_slots
    DROP CONSTRAINT IF EXISTS asset_slots_capacity;
ALTER TABLE asset_slots
    ADD CONSTRAINT asset_slots_capacity CHECK (
        legacy_offset IS NOT NULL
        OR (slot >= 2 AND slot < 1000000000000
            AND broker_account   = 1000000000 + slot * 1000000 + 1
            AND clearing_account = 1000000000 + slot * 1000000 + 3)
    );
