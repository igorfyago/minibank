-- THE ISSUER'S SIDE OF CLEARING.
--
-- An acquirer sends, later and in bulk, the authorisations it actually
-- completed. The issuer matches each line to a hold it is carrying, works out
-- what it owes, and keeps its own interchange for having lent the customer the
-- money and carried the risk.
--
-- WHY THE ISSUER COMPUTES THE TOTAL ITSELF RATHER THAN ACCEPTING THE ONE SENT.
-- The entire value of a clearing message is that two organisations who share no
-- database arrive at the same number independently and then compare. An issuer
-- that adopted the acquirer's total would make every reconciliation pass and
-- mean nothing: the disagreement is the signal, and there is no way to see it
-- without doing the arithmetic twice.

CREATE TABLE IF NOT EXISTS clearing_batches (
    -- the ACQUIRER's id for the batch, carried through, so a resend is
    -- recognisably the same batch rather than a second one
    id            TEXT PRIMARY KEY,
    acquirer      TEXT        NOT NULL DEFAULT 'minipay',
    currency      TEXT        NOT NULL,
    business_date DATE        NOT NULL,
    -- what the acquirer SAID, kept as sent
    claimed_gross NUMERIC(20,2) NOT NULL,
    claimed_net   NUMERIC(20,2) NOT NULL,
    -- what this issuer worked out for itself, from the holds it is actually
    -- carrying. The two are stored side by side and never merged.
    settled_gross NUMERIC(20,2) NOT NULL,
    interchange   NUMERIC(20,2) NOT NULL,
    settled_net   NUMERIC(20,2) NOT NULL,
    -- how many lines matched a real authorisation, and how many did not. A line
    -- the issuer cannot match is not an error to swallow: it is a dispute, and
    -- the count is what makes it visible.
    matched       INT         NOT NULL,
    unmatched     INT         NOT NULL,
    business_at   TIMESTAMPTZ NOT NULL,
    received_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT issuer_clearing_adds_up CHECK (settled_net = settled_gross - interchange)
);
CREATE INDEX IF NOT EXISTS issuer_clearing_disputed ON clearing_batches (received_at DESC)
    WHERE unmatched > 0 OR claimed_net <> settled_net;

-- Which authorisation each line cleared. One line per authorisation, ever,
-- because an acquirer resending a batch must not be paid twice for it.
CREATE TABLE IF NOT EXISTS clearing_lines (
    authorization_id UUID PRIMARY KEY,
    batch_id         TEXT NOT NULL REFERENCES clearing_batches(id),
    amount           NUMERIC(20,2) NOT NULL,
    interchange      NUMERIC(20,2) NOT NULL,
    matched          BOOLEAN NOT NULL
);
CREATE INDEX IF NOT EXISTS clearing_lines_by_batch ON clearing_lines (batch_id);
