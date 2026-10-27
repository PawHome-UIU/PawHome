-- =============================================================================
-- V7 -- a third kind of single-use link: the staff invitation.
--
-- WHY A NEW TYPE AND NOT A REUSED PASSWORD_RESET
--
-- The two look identical from the outside -- both end with somebody choosing a
-- password -- and they are not the same thing:
--
--   PASSWORD_RESET  an existing account, proven by the address, wants back in.
--                   Short-lived by design: 60 minutes.
--   INVITE          an account that has never been used at all. The person may
--                   be on leave; a one-hour window would strand them, and the
--                   admin would have to keep re-sending.
--
-- Sharing one type would also mean redeem() cannot tell them apart, so a
-- password-reset link would satisfy an invite and vice versa. TokenService
-- checks the type on redemption precisely so that cannot happen; giving the
-- invitation its own value is what makes that check mean something.
--
-- NOTHING ELSE CHANGES. An invited account is an ordinary users row -- there
-- is no "invited" column and there should not be. "Invited but not yet
-- accepted" is not a state of the person, it is the existence of an
-- outstanding token, and user_tokens already records exactly that. A column
-- would be a second copy of it, free to disagree.
-- =============================================================================

ALTER TABLE user_tokens
    MODIFY COLUMN token_type
        ENUM('EMAIL_VERIFICATION','PASSWORD_RESET','INVITE') NOT NULL;
