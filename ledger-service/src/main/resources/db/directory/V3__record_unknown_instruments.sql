-- AN AUTHORISATION RECORD IS A LOG OF WHAT WAS ASKED, NOT ONLY OF WHAT WAS VALID.
--
-- V2 put a foreign key from card_authorizations.token to card_instruments,
-- which reads as obviously correct and quietly throws away the most interesting
-- rows in the table. An attempt to authorise a card this bank has never issued
-- could not be written down at all: the insert failed, so the only record of
-- somebody presenting unknown cards was an exception in a log nobody reads.
--
-- Real issuers care about exactly those attempts. A run of authorisations
-- against tokens that do not exist is what card testing looks like from the
-- inside, and it is visible ONLY in the authorisation log. Refusing to store
-- the row makes the bank blind to the one pattern it should notice first.
--
-- So the token stays a plain column. The relationship is still real and still
-- queryable with a join; it is simply no longer enforced, because the table's
-- job is to record every question asked, including the ones with no valid
-- answer.
ALTER TABLE card_authorizations DROP CONSTRAINT IF EXISTS card_authorizations_token_fkey;

-- The query that finds somebody working through card numbers.
CREATE INDEX IF NOT EXISTS auth_declined_unknown ON card_authorizations (created_at DESC)
    WHERE state = 'declined' AND customer_id = 0;
