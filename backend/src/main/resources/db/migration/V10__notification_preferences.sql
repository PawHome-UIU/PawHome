-- =============================================================================
-- V10__notification_preferences.sql
--
-- WHY THIS EXISTS
-- ---------------
-- The Settings page has had notification toggles since the first mock. They
-- have never been saved anywhere, because until now there was nowhere to save
-- them: users has no preference column and notifications holds delivered
-- messages, not choices about them. Flipping a switch there did nothing and
-- said "Saved".
--
-- FOUR COLUMNS ON users, NOT A PREFERENCES TABLE. A preference is a single
-- valued attribute of exactly one user -- notify_boarding depends on the whole
-- key and nothing but the key -- so a separate table would be a second row to
-- fetch and a LEFT JOIN to get wrong on every read, in exchange for no
-- normalisation the columns do not already have. A table would earn its place
-- if preferences were per-shelter or per-pet. These are not.
--
-- THE OLD MOCK HAD FOUR TOGGLES AND TWO OF THEM ARE NOT HERE. "Matches" and
-- "Newsletter" were switches for mail this system does not send: there is no
-- recommendation email and no newsletter, so persisting those two would only
-- move the lie from the browser into the database. What replaces them is
-- notify_health, which turns a real thing off -- HealthReminderJob emails
-- overdue and due-soon vaccinations on a schedule, and until now there was no
-- way to stop it.
--
--   notify_applications  APPLICATION_SUBMITTED, APPLICATION_DECIDED
--   notify_boarding      BOARDING_DECIDED, BOARDING_UPDATE
--   notify_health        the vaccination reminders, which already exist
--   notify_email         the CHANNEL, not a topic: off means the bell still
--                        lights but no mail is sent. Orthogonal to the three
--                        above, which is why it is its own column rather than
--                        a fourth topic.
--
-- ALL DEFAULT TRUE, so every existing row keeps behaving exactly as it does
-- today. A migration that quietly opted everyone out of the mail they were
-- already receiving would be the worse kind of silent change.
--
-- STAFF_APPROVAL HAS NO SWITCH ON PURPOSE. It tells somebody their account
-- was approved or refused, which is not a preference -- an account they cannot
-- use and no explanation is not a quieter experience, just a confusing one.
-- =============================================================================

ALTER TABLE users
    ADD COLUMN notify_applications BOOLEAN NOT NULL DEFAULT TRUE
        COMMENT 'Adoption application events.',
    ADD COLUMN notify_boarding     BOOLEAN NOT NULL DEFAULT TRUE
        COMMENT 'Boarding decisions and stay updates.',
    ADD COLUMN notify_health       BOOLEAN NOT NULL DEFAULT TRUE
        COMMENT 'Vaccination reminders from HealthReminderJob.',
    ADD COLUMN notify_email        BOOLEAN NOT NULL DEFAULT TRUE
        COMMENT 'The email channel. FALSE still lights the in-app bell.';
