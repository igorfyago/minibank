-- The settlement consumer's side of the same story: a fill whose settlement
-- could not be attempted to a conclusion is kept, not printed and dropped.
-- Mirrored in Ledger.createSchemaOn so a test-built shard has it too.
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
