-- THE ISSUER'S SIDE OF A CARD.
--
-- minibank is about to start behaving as a card ISSUER: a merchant somewhere
-- else takes an order, its processor asks us whether the money is good, and we
-- approve or decline it against a credit limit we own. That makes this bank one
-- corner of the real four-party model, with minimart as the merchant, minipay as
-- the acquirer and processor, and a customer here as the cardholder.
--
-- WHY A TOKEN RATHER THAN A CUSTOMER ID. The processor must be able to ask
-- "is this instrument good for 79 euros" without ever learning who the person
-- is, which region they bank in, or what else they hold. A customer id would
-- leak all three and, worse, would be the join key that eventually tempts
-- somebody to read this database directly. The token is opaque, it means
-- nothing outside this table, and it is the ONLY thing that crosses the
-- boundary.
--
-- This lives in the DIRECTORY database rather than in a region shard, for the
-- same reason customer routing does: an inbound authorisation arrives knowing
-- only the token, so the lookup that finds the cardholder cannot itself require
-- knowing which region to look in.

CREATE TABLE IF NOT EXISTS card_instruments (
    -- what the outside world calls this card. Opaque, unguessable, and
    -- deliberately not derived from anything about the customer.
    token        TEXT PRIMARY KEY,
    customer_id  BIGINT      NOT NULL,
    -- what a merchant may safely display on a receipt, and nothing more
    brand_label  TEXT        NOT NULL DEFAULT 'minibank credit',
    last4        TEXT        NOT NULL,
    -- active | frozen | cancelled. A frozen card declines every authorisation
    -- without the acquirer needing to know why, which is the correct amount of
    -- detail to give somebody else's system about a customer's affairs.
    status       TEXT        NOT NULL DEFAULT 'active',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT card_status_ck CHECK (status IN ('active','frozen','cancelled'))
);

-- One live card per customer for now. Multiple cards are a real thing and this
-- constraint is what will have to be relaxed to support them, which is better
-- than discovering later that something quietly assumed uniqueness.
CREATE UNIQUE INDEX IF NOT EXISTS card_one_live_per_customer
    ON card_instruments (customer_id) WHERE status <> 'cancelled';

-- THE AUTHORISATION RECORD.
--
-- Kept on the issuer side because the issuer is the one who must be able to
-- answer, months later, what it approved and what became of it. An
-- authorisation that was approved and never captured or voided is money held
-- against a customer's limit for nothing, and the only way to find those is to
-- have written them down.
CREATE TABLE IF NOT EXISTS card_authorizations (
    -- minted by the ACQUIRER and carried through, so a retried authorisation is
    -- the same authorisation. The acquirer owns the idempotency of its own
    -- retries; we simply refuse to do the work twice.
    id            UUID PRIMARY KEY,
    token         TEXT        NOT NULL REFERENCES card_instruments(token),
    customer_id   BIGINT      NOT NULL,
    amount        NUMERIC(20,2) NOT NULL,
    currency      TEXT        NOT NULL,
    -- approved | declined | captured | voided
    state         TEXT        NOT NULL,
    -- why, in the issuer's own words, kept for the declines because "declined"
    -- with no reason is the most infuriating answer in payments
    reason        TEXT,
    -- who asked. Not a secret, and useful when an authorisation has to be
    -- explained to somebody a year later.
    acquirer      TEXT        NOT NULL DEFAULT 'minipay',
    business_at   TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    settled_at    TIMESTAMPTZ,
    CONSTRAINT auth_state_ck CHECK (state IN ('approved','declined','captured','voided'))
);
CREATE INDEX IF NOT EXISTS auth_by_customer ON card_authorizations (customer_id, created_at DESC);
-- the query that finds money held for nothing
CREATE INDEX IF NOT EXISTS auth_outstanding ON card_authorizations (state) WHERE state = 'approved';
