-- =============================================================================
-- V4__email_verification.sql
--
-- Adds email verification, and the token table that also serves password reset.
--
-- A THIRD AXIS, NOT A REPLACEMENT FOR THE OTHER TWO
-- -------------------------------------------------
-- The login rule was already two flags. It is now three, and they answer
-- genuinely different questions:
--
--   email_verified   do you actually own this address?      (proved by a link)
--   approval_status  did an administrator let you in?       (staff only)
--   is_active        is this account switched on?           (a kill switch)
--
-- Folding verification into approval_status would have been tempting and wrong:
-- an adopter is APPROVED the moment they register and still must not be able to
-- sign in with somebody else's email address.
-- =============================================================================

ALTER TABLE users
    ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE AFTER approval_status;


-- Every account that already exists is a seeded dev account (V2 and V3). They
-- have no real inbox to receive a link, so they start verified -- otherwise
-- this migration locks everyone out of their own project.
UPDATE users SET email_verified = TRUE;


-- =============================================================================
-- user_tokens -- one table for both single-use links.
--
-- THE TOKEN ITSELF IS NEVER STORED. Only a SHA-256 of it, for the same reason
-- passwords are hashed: a leaked database would otherwise be a stack of live
-- "click here to take over this account" links. The raw value exists once, in
-- the email, and is never written down anywhere else.
--
-- A hash is enough here where BCrypt is needed for passwords, because the token
-- is 256 bits of CSPRNG output -- there is no dictionary to attack and nothing
-- to guess. Passwords are low-entropy and chosen by humans; these are not.
-- =============================================================================
CREATE TABLE user_tokens (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    -- 64 hex characters of SHA-256.
    token_hash  CHAR(64)     NOT NULL,
    token_type  ENUM('EMAIL_VERIFICATION','PASSWORD_RESET') NOT NULL,
    expires_at  DATETIME     NOT NULL,
    -- Set when redeemed. A non-null value means "already used": the row is kept
    -- rather than deleted so a second click gets "this link was already used"
    -- instead of the identical "invalid link" a stranger sees.
    used_at     DATETIME         NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    -- Lookup is BY HASH, so this index is the whole read path.
    UNIQUE KEY uq_user_tokens_hash (token_hash),
    KEY idx_user_tokens_user (user_id, token_type),
    -- Sweeping expired rows scans this.
    KEY idx_user_tokens_expiry (expires_at),
    CONSTRAINT fk_user_tokens_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- =============================================================================
-- Health reminders already sent.
--
-- Without this the nightly job re-sends the same "Cooper's rabies booster is
-- due" email every single night until the shot is recorded. One row per
-- (vaccination, kind) makes the job idempotent.
-- =============================================================================
CREATE TABLE health_reminders_sent (
    id             BIGINT   NOT NULL AUTO_INCREMENT,
    vaccination_id BIGINT   NOT NULL,
    -- DUE_SOON goes out ahead of the date; OVERDUE after it. The same shot
    -- legitimately triggers both, once each.
    reminder_kind  ENUM('DUE_SOON','OVERDUE') NOT NULL,
    sent_to        VARCHAR(160) NOT NULL,
    sent_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_reminder_once (vaccination_id, reminder_kind),
    CONSTRAINT fk_reminder_vaccination FOREIGN KEY (vaccination_id)
        REFERENCES vaccinations (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
