-- V11 · the two lookups the statement page makes that had no index behind them.
--
-- Measured on a shard with 1402 entries, showing 40 rows for one account:
-- the shipped statement query touched 1522 shared buffers, and 1280 of them
-- were two Seq Scans on `entries` inside the LATERAL subqueries. Each LATERAL
-- runs once per displayed row and asks "which OTHER entry has this tx_id", and
-- `entries` had no index on tx_id at all · so answering it read the whole
-- table, forty times, twice over. 112,000 rows scanned to show 40.
--
-- That cost does not grow with the customer's history, which is what the
-- lesson-3 guard already watches. It grows with the SHARD's history, every
-- entry every customer ever wrote, which nothing was watching. A busy shard
-- makes every statement in it slower, including the statements of accounts
-- that have never done anything.
--
-- Same shape for the outbox: the statement asks for the departed event of a
-- cross-region payment by key, once per depart row, and `outbox` is indexed
-- only on (id) WHERE published_at IS NULL · a partial index the relay needs
-- and this lookup cannot use. So that read was a Seq Scan of the outbox too.
--
-- Both are plain covering-the-predicate indexes. Nothing about the ledger's
-- shape changes; these only give the planner a way to answer a question it
-- was already being asked.

CREATE INDEX IF NOT EXISTS idx_entries_tx ON entries(tx_id);
CREATE INDEX IF NOT EXISTS idx_outbox_key ON outbox(key);
