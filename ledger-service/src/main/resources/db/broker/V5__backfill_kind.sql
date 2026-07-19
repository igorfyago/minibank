-- V5 · a fill that was reconstructed rather than reported.
--
-- The bank used to acquire assets by writing them straight into the ledger,
-- with no order and no fill. Those holdings are real · the customer's money
-- moved and the ledger recorded it in double entry · but the broker has no
-- history for them, so its position, cost basis and P&L are all short by
-- exactly those trades.
--
-- The repair reconstructs the missing fills from the ledger's own entries:
-- the quantity and the cash are both legs of one recorded transaction, so
-- the price is their ratio and nothing is guessed. But a reconstructed fill
-- is NOT a fill a venue reported, and the difference has to survive in the
-- data rather than in somebody's memory of the migration. A P&L that counts
-- reconstructions as trades is lying about how much trading happened, which
-- is the same argument that gave 'compensation' its own kind in V3.
--
-- Note what is NOT here: no cash moves, no outbox row, no event. The money
-- side of these trades already happened, in the ledger, years of demo ago.
-- Re-announcing them would settle them a second time.
ALTER TABLE fills DROP CONSTRAINT IF EXISTS fills_kind_check;
ALTER TABLE fills ADD CONSTRAINT fills_kind_check
    CHECK (kind IN ('trade', 'compensation', 'backfill'));
