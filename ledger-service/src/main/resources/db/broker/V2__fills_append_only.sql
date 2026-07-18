-- V2 · a fill cannot be edited or deleted, and the DATABASE is what says so.
--
-- The ledger does this to entries (db/shard/V3__append_only.sql) for the same
-- reason: an audit trail that application code can rewrite is not an audit
-- trail. A wrong fill is corrected by recording the correcting fill, never by
-- UPDATE. That is also why positions is a projection: it is the mutable
-- summary, sitting on top of an immutable history that can always rebuild it.

CREATE OR REPLACE FUNCTION fills_are_append_only() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'fills are append-only: a fill is a fact a venue reported, correct it with another fill';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS no_fill_rewrites ON fills;
CREATE TRIGGER no_fill_rewrites
    BEFORE UPDATE OR DELETE ON fills
    FOR EACH ROW EXECUTE FUNCTION fills_are_append_only();
