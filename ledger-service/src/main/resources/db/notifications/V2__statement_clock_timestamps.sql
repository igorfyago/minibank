-- V2 · created_at is the instant the notification was STORED, not the instant the
-- storing transaction opened.
--
-- Same correction as db/shard/V7, for the same reason: now() is transaction time in
-- Postgres, frozen at BEGIN, and a column that exists to say when a row happened
-- should be read from the statement clock instead. The notifications consumer is
-- short and this column rarely drifted far, but it is one of the four clocks the
-- X-ray trace reads back, and a trace assembled from four clocks has no room for a
-- timestamp that is merely usually close.

ALTER TABLE notifications ALTER COLUMN created_at SET DEFAULT clock_timestamp();
