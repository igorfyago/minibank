-- V1 · the directory: the routing truth (stage 6).
-- customer_id -> shard, plus a moving flag that a relocation raises for the
-- milliseconds of a write-pause. Residency is a FACT, so routing is a LOOKUP.

CREATE TABLE IF NOT EXISTS customers (
    customer_id BIGINT PRIMARY KEY,
    owner       TEXT NOT NULL,
    shard       INT  NOT NULL,
    moving      BOOLEAN NOT NULL DEFAULT false
);
