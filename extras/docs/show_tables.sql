-- =============================================================================
-- SHOW ME THE DATABASE -- every table, view, column, key and constraint.
--
-- Run with:
--   docker exec -it pawhome-mysql mysql -u pawhome -ppawhome_dev_pw pawhome
--
-- Paste ONE SECTION at a time. Each is independent; none of them writes
-- anything, so nothing here can damage the database.
--
-- Everything reads from information_schema, which is MySQL's own catalogue of
-- itself -- the same place SHOW TABLES gets its answer, but queryable, so the
-- output can be counted, sorted and filtered. That is also why this file is
-- worth having for the course: it is the database describing its own schema.
-- =============================================================================


-- =============================================================================
-- 1. THE HEADLINE -- how big is this schema?
-- =============================================================================
SELECT
  (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE')        AS tables,
  (SELECT COUNT(*) FROM information_schema.views
    WHERE table_schema = DATABASE())                                      AS views,
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE())                                      AS columns_total,
  (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND constraint_type = 'FOREIGN KEY')  AS foreign_keys,
  (SELECT COUNT(*) FROM information_schema.check_constraints
    WHERE constraint_schema = DATABASE())                                 AS check_constraints,
  (SELECT COUNT(DISTINCT table_name, index_name) FROM information_schema.statistics
    WHERE table_schema = DATABASE())                                      AS indexes_total;
-- Migrations are counted in section 9. They are deliberately NOT in this
-- query: flyway_schema_history is Flyway's table, not part of the schema, and
-- a database built by running the .sql files by hand does not have it -- which
-- would make this whole section fail rather than just that one number.


-- =============================================================================
-- 2. EVERY TABLE, with its row count and how many of each thing it has.
--
-- table_rows FROM information_schema IS AN ESTIMATE for InnoDB -- it comes from
-- the storage engine's statistics, not a count, and can be out by a lot on a
-- table that was just loaded. Section 3 does the exact count. This one is for
-- the shape of the schema, where an estimate is fine and instant.
-- =============================================================================
SELECT
    t.table_name                                       AS `table`,
    t.table_rows                                       AS `rows_est`,
    (SELECT COUNT(*) FROM information_schema.columns c
      WHERE c.table_schema = t.table_schema AND c.table_name = t.table_name)
                                                       AS cols,
    (SELECT COUNT(*) FROM information_schema.table_constraints k
      WHERE k.table_schema = t.table_schema AND k.table_name = t.table_name
        AND k.constraint_type = 'FOREIGN KEY')         AS fks,
    (SELECT COUNT(DISTINCT s.index_name) FROM information_schema.statistics s
      WHERE s.table_schema = t.table_schema AND s.table_name = t.table_name)
                                                       AS idx,
    ROUND((t.data_length + t.index_length) / 1024)      AS kb
FROM      information_schema.tables t
WHERE     t.table_schema = DATABASE()
  AND     t.table_type   = 'BASE TABLE'
ORDER BY  t.table_name;


-- =============================================================================
-- 3. EXACT ROW COUNTS for every table, in one result.
--
-- Written out by hand rather than generated, because COUNT(*) cannot take a
-- table name from a variable -- doing it dynamically needs a prepared statement
-- per table, which is a lot of machinery for a list you can read.
--
-- Read it as "what is actually in the database right now". A zero here on a
-- table you expected to have data in is the fastest way to find a feature you
-- have not exercised yet.
-- =============================================================================
SELECT 'adoption_applications' AS `table`, COUNT(*) AS `rows` FROM adoption_applications
UNION ALL SELECT 'adoptions',             COUNT(*) FROM adoptions
UNION ALL SELECT 'audit_log',             COUNT(*) FROM audit_log
UNION ALL SELECT 'billing_slip_items',    COUNT(*) FROM billing_slip_items
UNION ALL SELECT 'billing_slips',         COUNT(*) FROM billing_slips
UNION ALL SELECT 'boarding_charges',      COUNT(*) FROM boarding_charges
UNION ALL SELECT 'boarding_instructions', COUNT(*) FROM boarding_instructions
UNION ALL SELECT 'boarding_requests',     COUNT(*) FROM boarding_requests
UNION ALL SELECT 'boarding_updates',      COUNT(*) FROM boarding_updates
UNION ALL SELECT 'care_logs',             COUNT(*) FROM care_logs
UNION ALL SELECT 'care_tasks',            COUNT(*) FROM care_tasks
UNION ALL SELECT 'daycare_attendance',    COUNT(*) FROM daycare_attendance
UNION ALL SELECT 'daycare_subscriptions', COUNT(*) FROM daycare_subscriptions
UNION ALL SELECT 'favorites',             COUNT(*) FROM favorites
UNION ALL SELECT 'health_reminders_sent', COUNT(*) FROM health_reminders_sent
UNION ALL SELECT 'notifications',         COUNT(*) FROM notifications
UNION ALL SELECT 'option_traits',         COUNT(*) FROM option_traits
UNION ALL SELECT 'pet_photos',            COUNT(*) FROM pet_photos
UNION ALL SELECT 'pet_traits',            COUNT(*) FROM pet_traits
UNION ALL SELECT 'pets',                  COUNT(*) FROM pets
UNION ALL SELECT 'quiz_answers',          COUNT(*) FROM quiz_answers
UNION ALL SELECT 'quiz_attempts',         COUNT(*) FROM quiz_attempts
UNION ALL SELECT 'quiz_options',          COUNT(*) FROM quiz_options
UNION ALL SELECT 'quiz_questions',        COUNT(*) FROM quiz_questions
UNION ALL SELECT 'reports',               COUNT(*) FROM reports
UNION ALL SELECT 'shelter_suites',        COUNT(*) FROM shelter_suites
UNION ALL SELECT 'shelters',              COUNT(*) FROM shelters
UNION ALL SELECT 'traits',                COUNT(*) FROM traits
UNION ALL SELECT 'user_tokens',           COUNT(*) FROM user_tokens
UNION ALL SELECT 'users',                 COUNT(*) FROM users
UNION ALL SELECT 'vaccinations',          COUNT(*) FROM vaccinations
UNION ALL SELECT 'vaccine_types',         COUNT(*) FROM vaccine_types
ORDER BY `rows` DESC, `table`;


-- =============================================================================
-- 4. EVERY COLUMN OF EVERY TABLE.
--
-- This is the long one -- around 313 rows. It is the whole data dictionary, and
-- it is what to hand someone who asks "what does your schema look like".
--
-- `key` reads PRI / UNI / MUL: primary, unique, or the first column of an
-- ordinary index. `extra` is where auto_increment and the GENERATED STORED
-- columns show themselves.
-- =============================================================================
SELECT
    table_name                    AS `table`,
    ordinal_position              AS `#`,
    column_name                   AS `column`,
    column_type                   AS `type`,
    is_nullable                   AS `null`,
    COALESCE(column_default, '')  AS `default`,
    column_key                    AS `key`,
    extra
FROM      information_schema.columns
WHERE     table_schema = DATABASE()
ORDER BY  table_name, ordinal_position;

-- ONE TABLE AT A TIME, which is usually what you actually want:
--   SHOW CREATE TABLE pets\G
--   DESCRIBE pets;


-- =============================================================================
-- 5. EVERY FOREIGN KEY -- the shape of the ERD, as rows.
--
-- The delete_rule column is the interesting one and the reason to read this
-- rather than the diagram: RESTRICT means the parent cannot be deleted while
-- children exist, CASCADE means the children go too, SET NULL means the link is
-- cut and the child survives. Those three choices are the whole argument about
-- what this schema protects.
-- =============================================================================
SELECT
    k.constraint_name                                   AS fk,
    CONCAT(k.table_name, '.', k.column_name)            AS child,
    CONCAT(k.referenced_table_name, '.',
           k.referenced_column_name)                    AS parent,
    r.delete_rule,
    r.update_rule
FROM        information_schema.key_column_usage k
JOIN        information_schema.referential_constraints r
       ON   r.constraint_schema = k.table_schema
      AND   r.constraint_name   = k.constraint_name
WHERE       k.table_schema           = DATABASE()
  AND       k.referenced_table_name IS NOT NULL
ORDER BY    r.delete_rule, k.table_name, k.constraint_name;

-- Count them by policy:
SELECT r.delete_rule, COUNT(*) AS fks
FROM      information_schema.referential_constraints r
WHERE     r.constraint_schema = DATABASE()
GROUP BY  r.delete_rule
ORDER BY  fks DESC;


-- =============================================================================
-- 6. EVERY CHECK CONSTRAINT -- the rules the database enforces by itself.
--
-- Worth printing for a database course specifically: these are the business
-- rules that do NOT live in Java. A bug in the application cannot write a row
-- that breaks one of these, which is the argument for putting them here.
-- =============================================================================
SELECT
    tc.table_name  AS `table`,
    cc.constraint_name,
    cc.check_clause
FROM      information_schema.check_constraints cc
JOIN      information_schema.table_constraints tc
     ON   tc.constraint_schema = cc.constraint_schema
    AND   tc.constraint_name   = cc.constraint_name
WHERE     cc.constraint_schema = DATABASE()
ORDER BY  tc.table_name, cc.constraint_name;


-- =============================================================================
-- 7. EVERY INDEX, one row per index (not per column).
--
-- GROUP_CONCAT with ORDER BY seq_in_index is the point: a composite index is
-- only useful left to right, so (shelter_id, status) and (status, shelter_id)
-- are different indexes answering different questions. Printing the columns in
-- order is the only way to see which one you have.
-- =============================================================================
SELECT
    table_name                                              AS `table`,
    index_name,
    GROUP_CONCAT(column_name ORDER BY seq_in_index)         AS columns_in_order,
    IF(MAX(non_unique) = 0, 'UNIQUE', '')                   AS uniq,
    index_type
FROM      information_schema.statistics
WHERE     table_schema = DATABASE()
GROUP BY  table_name, index_name, index_type
ORDER BY  table_name, (index_name = 'PRIMARY') DESC, index_name;


-- =============================================================================
-- 8. THE VIEWS, and their definitions.
--
-- Four of them, and all four are read by application code:
--   v_adopter_traits    PetService.matches / .myTraits, QuizTakingService
--   v_pet_listing       PetService.browse / .publicPet (the public catalogue)
--   v_shelter_revenue   AnalyticsService, the admin Billing screen
--   v_daycare_day       DaycareService.board, the staff attendance history
-- =============================================================================
SELECT table_name AS `view`, CHAR_LENGTH(view_definition) AS definition_chars
FROM      information_schema.views
WHERE     table_schema = DATABASE()
ORDER BY  table_name;

-- The full text of one (\G prints it down the page instead of in a wide row):
--   SELECT view_definition FROM information_schema.views
--    WHERE table_schema = DATABASE() AND table_name = 'v_pet_listing'\G
--
-- Or just look at each:
--   SELECT * FROM v_pet_listing     LIMIT 5;
--   SELECT * FROM v_shelter_revenue LIMIT 5;
--   SELECT * FROM v_daycare_day     LIMIT 5;
--   SELECT * FROM v_adopter_traits  LIMIT 5;


-- =============================================================================
-- 9. WHICH MIGRATIONS HAVE RUN.
--
-- Flyway's own table. `success = 0` on any row means a migration failed
-- half-way and the schema is in an unknown state -- that is the row to look for
-- first when the application will not start.
-- =============================================================================
SELECT  installed_rank AS `#`, version, description,
        success, ROUND(execution_time / 1000, 2) AS secs, installed_on
FROM      flyway_schema_history
ORDER BY  installed_rank;


-- =============================================================================
-- 10. THE ENUM COLUMNS, spelled out.
--
-- MySQL stores an ENUM as the INDEX of the value, not its text, so the ORDER of
-- these lists is part of the data. That is why V13 and V18 both APPEND a new
-- value at the end rather than inserting it alphabetically: inserting one in
-- the middle renumbers every value after it and silently changes the meaning of
-- every existing row.
-- =============================================================================
SELECT    table_name AS `table`, column_name AS `column`, column_type AS values_in_order
FROM      information_schema.columns
WHERE     table_schema = DATABASE()
  AND     data_type    = 'enum'
ORDER BY  table_name, column_name;


-- =============================================================================
-- 11. GENERATED COLUMNS -- values the database computes, never stored by hand.
-- =============================================================================
SELECT    table_name AS `table`, column_name AS `column`,
          generation_expression AS computed_as, extra
FROM      information_schema.columns
WHERE     table_schema = DATABASE()
  AND     generation_expression <> ''
ORDER BY  table_name, column_name;


-- =============================================================================
-- 12. ORPHAN CHECK -- tables nothing points at, and tables that point nowhere.
--
-- A table with no incoming AND no outgoing foreign key is either a lookup table
-- (fine -- traits, vaccine_types) or something that was never wired up (not
-- fine). Worth a look before a viva.
-- =============================================================================
SELECT
    t.table_name AS `table`,
    (SELECT COUNT(*) FROM information_schema.key_column_usage k
      WHERE k.table_schema = DATABASE() AND k.table_name = t.table_name
        AND k.referenced_table_name IS NOT NULL)            AS points_out,
    (SELECT COUNT(*) FROM information_schema.key_column_usage k
      WHERE k.table_schema = DATABASE()
        AND k.referenced_table_name = t.table_name)         AS pointed_at
FROM      information_schema.tables t
WHERE     t.table_schema = DATABASE() AND t.table_type = 'BASE TABLE'
ORDER BY  pointed_at DESC, points_out DESC, t.table_name;
