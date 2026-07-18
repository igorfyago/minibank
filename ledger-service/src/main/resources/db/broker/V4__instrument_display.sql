-- V4 · an instrument needs a NAME and a place where it trades.
--
-- The catalog until now carried only what the MACHINE needs: the symbol to
-- route on and the asset_code to settle against. A portfolio screen needs
-- what the HUMAN needs · "AAPL / NASDAQ.NMS / APPLE INC" is three different
-- facts, and the first is the only one we stored.
--
-- Both columns are nullable rather than NOT NULL DEFAULT ''. An instrument
-- whose exchange we do not know should say NULL and let the UI omit the
-- field, because '' renders as a blank cell that looks like a bug and a
-- placeholder like 'UNKNOWN' renders as a venue that does not exist.
ALTER TABLE instruments ADD COLUMN IF NOT EXISTS display_name TEXT;
ALTER TABLE instruments ADD COLUMN IF NOT EXISTS exchange     TEXT;
