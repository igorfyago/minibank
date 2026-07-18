-- V1 · the notifications read model (stage 2): a separate service, a separate
-- database. The event_key (the tx id) is the PRIMARY KEY, which is the whole
-- idempotency story: INSERT ... ON CONFLICT DO NOTHING dedupes redeliveries.

CREATE TABLE IF NOT EXISTS notifications (
    event_key  TEXT PRIMARY KEY,
    message    TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
