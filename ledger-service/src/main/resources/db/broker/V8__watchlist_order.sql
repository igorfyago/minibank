-- A watchlist has an ORDER, and until now it did not have one it could keep.
--
-- The read was ORDER BY added_at, which looks right and is not, because
-- Postgres now() is the TRANSACTION's start time, not the statement's. Every
-- row of a batched insert therefore carries an identical added_at, and
-- ordering by a column where every value ties leaves the order undefined ·
-- the planner may return them however it likes. Importing [SPY, QQQ] and
-- reading back [QQQ, SPY] is not a flaky test, it is the absence of a fact.
--
-- So record the fact. A BIGSERIAL is monotonic per INSERT rather than per
-- transaction, which is exactly the distinction that was missing. Existing
-- rows are numbered by their added_at so nobody's current list is reshuffled
-- by this migration; ties among them keep whatever order they already had,
-- which is the best that can be said for data that never recorded it.

ALTER TABLE watchlist ADD COLUMN IF NOT EXISTS seq BIGSERIAL;

-- Backfill deterministically: oldest first, and symbol as the tiebreaker so
-- that running this on two replicas of the same data gives the same answer.
WITH ordered AS (
    SELECT customer_id, symbol,
           ROW_NUMBER() OVER (ORDER BY added_at, customer_id, symbol) AS rn
    FROM watchlist
)
UPDATE watchlist w
SET seq = o.rn
FROM ordered o
WHERE w.customer_id = o.customer_id AND w.symbol = o.symbol;

-- keep the sequence ahead of the backfilled values
SELECT setval(pg_get_serial_sequence('watchlist', 'seq'),
              GREATEST((SELECT COALESCE(MAX(seq), 0) FROM watchlist), 1));

CREATE INDEX IF NOT EXISTS idx_watchlist_order ON watchlist(customer_id, seq);
