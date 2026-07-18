-- V3 · compliance is schema too: the ledger is append-only, enforced by the
-- database, not by politeness. UPDATE or DELETE on a row raises. Corrections
-- are reversing entries · history grows, it never changes. (TRUNCATE stays
-- possible for the demo reset ritual; row UPDATE/DELETE is what tampering is.)

CREATE OR REPLACE FUNCTION ledger_immutable() RETURNS trigger AS $$
BEGIN RAISE EXCEPTION 'the ledger is append-only'; END
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS entries_immutable ON entries;
CREATE TRIGGER entries_immutable BEFORE UPDATE OR DELETE ON entries
    FOR EACH ROW EXECUTE FUNCTION ledger_immutable();

DROP TRIGGER IF EXISTS transactions_immutable ON transactions;
CREATE TRIGGER transactions_immutable BEFORE UPDATE OR DELETE ON transactions
    FOR EACH ROW EXECUTE FUNCTION ledger_immutable();
