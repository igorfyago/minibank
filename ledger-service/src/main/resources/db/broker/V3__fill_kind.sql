-- V3 · a fill needs to say what KIND of fill it is.
--
-- The saga can fail on the money side after the venue has already filled,
-- and the position has to come back. Fills are append-only, so it comes back
-- the only honest way: by recording the reversal, not by deleting history.
--
-- That reversal is not a trade. Nobody bought or sold anything; the bank
-- refused to settle and the book was put back. Labelling it 'compensation'
-- keeps that distinction visible in the one place it matters · a P&L that
-- counts compensations as trades is a P&L that lies about how much trading
-- happened.
ALTER TABLE fills ADD COLUMN IF NOT EXISTS kind TEXT NOT NULL DEFAULT 'trade';
ALTER TABLE fills DROP CONSTRAINT IF EXISTS fills_kind_check;
ALTER TABLE fills ADD CONSTRAINT fills_kind_check CHECK (kind IN ('trade', 'compensation'));
