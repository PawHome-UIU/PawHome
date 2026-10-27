-- =============================================================================
-- V5__fix_token_hash_type.sql
--
-- Fixes a schema/entity mismatch that stopped the application booting:
--
--   Schema-validation: wrong column type encountered in column [token_hash]
--   in table [user_tokens]; found [char (Types#CHAR)],
--   but expecting [varchar(64) (Types#VARCHAR)]
--
-- V4 declared CHAR(64) because a SHA-256 hex digest is always exactly 64
-- characters. UserToken.tokenHash is a plain String with @Column(length = 64),
-- which Hibernate maps to VARCHAR -- and ddl-auto: validate compares the two
-- and refuses to start when they disagree. Working as designed: the entity and
-- the migration had drifted, and it said so instead of guessing.
--
-- WHY THIS IS A NEW FILE AND NOT AN EDIT TO V4
-- --------------------------------------------
-- V4 has already run, so Flyway has stored its checksum. Changing that file now
-- makes every subsequent startup fail with a validation error about the
-- migration itself, which is a worse problem than the one being fixed. A
-- migration that has run is history; history gets appended to.
--
-- VARCHAR IS ALSO THE BETTER CHOICE HERE, not merely the one that agrees with
-- the entity. The column is utf8mb4, where CHAR(64) reserves four bytes per
-- character -- 256 bytes on every row, for a value that is always 64 bytes of
-- ASCII hex. VARCHAR(64) stores the 64 bytes plus a length byte.
-- =============================================================================

ALTER TABLE user_tokens
    MODIFY COLUMN token_hash VARCHAR(64) NOT NULL;

-- The unique index on token_hash is unaffected: MODIFY COLUMN rebuilds the
-- column in place and MySQL carries the index over. Nothing needs re-creating,
-- and any rows already there keep their values.
