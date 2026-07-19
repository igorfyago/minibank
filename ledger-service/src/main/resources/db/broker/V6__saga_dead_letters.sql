-- A saga step that would not complete, kept instead of printed.
--
-- The settlements consumer used to swallow every exception and let the offset
-- advance, so a compensation that threw was gone: no retry, no record, no
-- alarm. One row per (consumer, event) so a redelivered poison record counts
-- attempts onto the same row rather than growing the table.
CREATE TABLE IF NOT EXISTS saga_dead_letters (
    id         BIGSERIAL PRIMARY KEY,
    consumer   TEXT NOT NULL,
    event_key  TEXT NOT NULL,
    topic      TEXT NOT NULL,
    payload    TEXT NOT NULL,
    error      TEXT NOT NULL,
    attempts   INT  NOT NULL,
    first_seen TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (consumer, event_key)
);
