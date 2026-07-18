-- V1 · THE BROKER'S OWN DATABASE.
--
-- Database per service, again: this schema lives on the control-plane
-- Postgres beside minibank_directory and minibank_notifications, and the
-- ledger cannot read it. The boundary is drawn around DATA OWNERSHIP, and
-- what the broker owns is the ORDER LIFECYCLE and COST BASIS.
--
-- What it deliberately does NOT own: how much of an asset you hold. That is
-- money, it lives in the ledger, and the ledger already proves it (multi-
-- currency double entry, sum-zero per currency). The broker records how you
-- came to hold it and what it cost you · which is exactly what a ledger
-- refuses to model, because "you are up 120 euro" is an opinion about the
-- past, not a balance.

-- ---------------------------------------------------------------- catalog
CREATE TABLE IF NOT EXISTS instruments (
    symbol      TEXT PRIMARY KEY,                 -- 'BTC', 'AAPL'
    kind        TEXT NOT NULL,                    -- 'crypto' | 'equity'
    asset_code  TEXT NOT NULL,                    -- the LEDGER currency of the asset leg
    settle_ccy  TEXT NOT NULL DEFAULT 'EUR',      -- what the cash leg is denominated in
    multiplier  NUMERIC(20,8) NOT NULL DEFAULT 1, -- 100 for an option contract
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ------------------------------------------------------- who is this, really
-- The desk knows an anonymous browser string; the bank knows a customer id.
-- Somebody has to hold the mapping, and it is the service that needs it.
-- This is also exactly where real authentication would later drop in: the
-- rest of the schema already speaks customer_id and would not change.
CREATE TABLE IF NOT EXISTS account_link (
    desk_session TEXT PRIMARY KEY,
    customer_id  BIGINT NOT NULL,
    linked_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_link_customer ON account_link(customer_id);

-- ------------------------------------------------------------------ orders
-- client_order_id is the idempotency gate, and the CLIENT chooses it · the
-- same doctrine as the ledger's txId. A retried POST is not a second order.
CREATE TABLE IF NOT EXISTS orders (
    id              UUID PRIMARY KEY,
    client_order_id TEXT NOT NULL UNIQUE,
    customer_id     BIGINT NOT NULL,
    symbol          TEXT NOT NULL REFERENCES instruments(symbol),
    side            TEXT NOT NULL CHECK (side IN ('buy', 'sell')),
    -- one of the two: qty for "3 shares", notional for "50 euro worth"
    qty             NUMERIC(20,8) CHECK (qty IS NULL OR qty > 0),
    notional        NUMERIC(20,8) CHECK (notional IS NULL OR notional > 0),
    order_type      TEXT NOT NULL CHECK (order_type IN ('market', 'limit')),
    limit_px        NUMERIC(20,8) CHECK (limit_px IS NULL OR limit_px > 0),
    status          TEXT NOT NULL CHECK (status IN
                        ('accepted', 'filled', 'settled', 'rejected', 'cancelled')),
    reject_reason   TEXT,
    venue           TEXT NOT NULL,                -- which adapter routed it
    venue_ref       TEXT,                         -- the venue's own id, if any
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT orders_size CHECK (qty IS NOT NULL OR notional IS NOT NULL),
    CONSTRAINT orders_limit_needs_price CHECK (order_type <> 'limit' OR limit_px IS NOT NULL)
);
CREATE INDEX IF NOT EXISTS idx_orders_customer ON orders(customer_id, id);
CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status) WHERE status IN ('accepted', 'filled');

-- ------------------------------------------------------------------- fills
-- Append-only, like entries. A fill is a fact reported by a venue: it
-- happened, at a price, at a time. Facts do not get edited.
CREATE TABLE IF NOT EXISTS fills (
    id            UUID PRIMARY KEY,
    order_id      UUID NOT NULL REFERENCES orders(id),
    qty           NUMERIC(20,8) NOT NULL CHECK (qty > 0),
    price         NUMERIC(20,8) NOT NULL CHECK (price > 0),
    fee           NUMERIC(20,8) NOT NULL DEFAULT 0 CHECK (fee >= 0),
    venue_fill_id TEXT,
    executed_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_fills_order ON fills(order_id, id);

-- --------------------------------------------------------------- positions
-- A PROJECTION of fills, exactly as the ledger's cached balance is a
-- projection of entries: derived, fast to read, and reconcilable against the
-- fills that produced it. Broker.audit() recomputes it from scratch, which
-- is the same trick as the drift audit.
--
-- Cost basis is AVERAGE COST, not FIFO. Average is what a retail app shows
-- you next to a fractional position, and it needs one row instead of a lot
-- table. FIFO matters when tax lots matter, and that is a different product.
CREATE TABLE IF NOT EXISTS positions (
    customer_id  BIGINT NOT NULL,
    symbol       TEXT NOT NULL REFERENCES instruments(symbol),
    qty          NUMERIC(20,8) NOT NULL DEFAULT 0,
    cost_basis   NUMERIC(20,8) NOT NULL DEFAULT 0,   -- total cost of the OPEN qty
    realized_pnl NUMERIC(20,8) NOT NULL DEFAULT 0,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (customer_id, symbol),
    CONSTRAINT positions_no_short CHECK (qty >= 0)
);

-- --------------------------------------------------------------- watchlist
-- Was localStorage on one browser, which meant it was not really data.
CREATE TABLE IF NOT EXISTS watchlist (
    customer_id BIGINT NOT NULL,
    symbol      TEXT NOT NULL,
    added_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (customer_id, symbol)
);

-- ------------------------------------------------------------------ outbox
-- The same transactional outbox the shards use. A fill and its event are
-- written in ONE commit here, and a relay ships the event afterwards, so the
-- ledger can settle the cash without the two services sharing a transaction
-- they cannot share.
CREATE TABLE IF NOT EXISTS outbox (
    id           BIGSERIAL PRIMARY KEY,
    topic        TEXT NOT NULL,
    key          TEXT NOT NULL,
    payload      TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_broker_outbox_unpublished ON outbox(id) WHERE published_at IS NULL;
