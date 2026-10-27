-- =============================================================================
-- SELECT * FROM every table in the PawHome database.
--
-- Run with:
--   docker exec -it pawhome-mysql mysql -u pawhome -ppawhome_dev_pw pawhome
--
-- You can paste the whole file at once -- every statement is a read, nothing
-- here writes. You will get one result set per table, in the order below.
--
-- IF THE OUTPUT IS AN UNREADABLE WALL OF PIPES, that is the wide tables (pets
-- has 24 columns, adoption_applications 22). Two fixes:
--   * end a statement with \G instead of ; to print it down the page
--       SELECT * FROM pets\G
--   * or start the client with --table --pager='less -S' to scroll sideways
--
-- A TABLE THAT COMES BACK EMPTY is not broken -- it is a feature you have not
-- used yet. health_reminders_sent stays empty until the reminder job runs,
-- reports until someone generates one, audit_log until an action is recorded.
-- =============================================================================


-- ---------------------------------------------------------------------------
-- PEOPLE AND PLACES
-- ---------------------------------------------------------------------------
SELECT * FROM users;
SELECT * FROM shelters;
SELECT * FROM shelter_suites;

-- ---------------------------------------------------------------------------
-- PETS
-- ---------------------------------------------------------------------------
SELECT * FROM pets;
SELECT * FROM pet_photos;
SELECT * FROM traits;
SELECT * FROM pet_traits;
SELECT * FROM favorites;

-- ---------------------------------------------------------------------------
-- QUIZ
-- ---------------------------------------------------------------------------
SELECT * FROM quiz_questions;
SELECT * FROM quiz_options;
SELECT * FROM option_traits;
SELECT * FROM quiz_attempts;
SELECT * FROM quiz_answers;

-- ---------------------------------------------------------------------------
-- ADOPTION
-- ---------------------------------------------------------------------------
SELECT * FROM adoption_applications;
SELECT * FROM adoptions;

-- ---------------------------------------------------------------------------
-- BOARDING
-- ---------------------------------------------------------------------------
SELECT * FROM boarding_requests;
SELECT * FROM boarding_instructions;
SELECT * FROM boarding_updates;
SELECT * FROM boarding_charges;

-- ---------------------------------------------------------------------------
-- DAILY CARE
-- ---------------------------------------------------------------------------
SELECT * FROM care_tasks;
SELECT * FROM care_logs;

-- ---------------------------------------------------------------------------
-- HEALTH
-- ---------------------------------------------------------------------------
SELECT * FROM vaccine_types;
SELECT * FROM vaccinations;
SELECT * FROM health_reminders_sent;

-- ---------------------------------------------------------------------------
-- DAYCARE
-- ---------------------------------------------------------------------------
SELECT * FROM daycare_subscriptions;
SELECT * FROM daycare_attendance;

-- ---------------------------------------------------------------------------
-- MONEY
-- ---------------------------------------------------------------------------
SELECT * FROM billing_slips;
SELECT * FROM billing_slip_items;

-- ---------------------------------------------------------------------------
-- SYSTEM
-- ---------------------------------------------------------------------------
SELECT * FROM notifications;
SELECT * FROM user_tokens;
SELECT * FROM audit_log;
SELECT * FROM reports;


-- =============================================================================
-- THE FOUR VIEWS.
--
-- Not tables -- each is a stored SELECT, re-run every time you read it, so
-- these show the same data as above with the joins already done. All four are
-- queried by application code.
-- =============================================================================
SELECT * FROM v_adopter_traits;
SELECT * FROM v_pet_listing;
SELECT * FROM v_shelter_revenue;
SELECT * FROM v_daycare_day;


-- =============================================================================
-- IF YOU WANT THE COUNTS FIRST, to see which tables have anything in them,
-- run docs/show_tables.sql section 3 instead -- one result, 32 rows.
-- =============================================================================
