-- V7 · created_at must mean WHEN THIS ROW HAPPENED, not when its transaction opened.
--
-- Postgres now() is transaction_timestamp(): it is fixed at BEGIN and returns the
-- same value for every statement in the transaction, however long that transaction
-- goes on to run. Every created_at in this schema defaulted to it.
--
-- That is the wrong reading for these columns, and it is not academic. The saga's
-- arrival, Shard.arrive(), opens a transaction, claims the txId, and then takes
-- SELECT ... FOR UPDATE on IN_TRANSIT · the single row that every saga on the shard
-- must serialise through. On a busy region it waits there, sometimes for a long time,
-- and then commits. Stamped with now(), the row it writes claims to have happened at
-- the instant it began queueing. The X-ray read those instants back and drew a
-- cross-region transfer arriving in uk BEFORE eu had published the message that
-- caused it: an effect before its cause, on the live site.
--
-- clock_timestamp() is the statement clock. It advances inside the transaction, so
-- the stamp is taken when the row is actually inserted rather than when its
-- transaction opened. The same distinction was already learned once in this repo,
-- for the broker's watchlist ordering (db/broker/V8__watchlist_order.sql).
--
-- The bank creates these tables with CREATE TABLE IF NOT EXISTS, which is a no-op on
-- a database that already has them, so an existing shard would never pick the new
-- default up from the CREATE alone. Hence explicit ALTERs. Existing rows are left
-- exactly as they are: their recorded instants are the only record of themselves
-- that exists, and rewriting history to look tidier is the opposite of an audit log.
--
-- NOTE ON SCOPE, so the next reader does not over-trust this. This makes each
-- timestamp an honest reading OF ITS OWN CLOCK. It does not make timestamps from
-- DIFFERENT machines comparable, and the eu shard, the uk shard, the notifications
-- database and the relay's JVM are four different clocks. The trace therefore orders
-- its steps by a declared causal depth and treats the instants as a tiebreaker only ·
-- see HttpApi.causalDepth.

ALTER TABLE transactions ALTER COLUMN created_at SET DEFAULT clock_timestamp();
ALTER TABLE entries      ALTER COLUMN created_at SET DEFAULT clock_timestamp();
ALTER TABLE outbox       ALTER COLUMN created_at SET DEFAULT clock_timestamp();
