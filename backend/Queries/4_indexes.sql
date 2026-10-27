-- ===========================================================================
-- PawHome — THE INDEXES
-- ===========================================================================
--
-- 124 indexes across 32 tables, read from the live database.
--   32 primary keys
--   19 unique indexes besides the primary keys
--   73 ordinary indexes
--   41 span more than one column
-- 
-- EVERY INDEX HERE EXISTS FOR A NAMED QUERY. Column ORDER matters as much as
-- the index existing: a composite index is only useful left to right, so
-- (status, species, size) can serve a query filtering on status alone, or on
-- status and species — but not one filtering on size alone.
-- 
-- TWO OF THEM ARE NOT OPTIMISATIONS AT ALL. uq_pet_cover and uq_one_open_app
-- enforce business rules that MySQL has no other way to state, using a
-- generated column that is NULL exactly when the row should be exempt.
-- NULLs do not collide in a unique index. See their notes below.
-- 
-- Indexes created by a foreign key are named fk_*; MySQL requires an index
-- on
-- the referencing column and creates one automatically if none exists.
-- 
-- THIS FILE IS A REFERENCE, NOT A MIGRATION. The indexes already exist —
-- they
-- are created by the migrations in backend/src/main/resources/db/migration.
-- Primary keys are shown commented out, because CREATE TABLE already made
-- them. Replaying the CREATE INDEX statements against a built schema is
-- harmless but every one will report "Duplicate key name", which is simply
-- MySQL confirming the index is already there under that exact name.


-- ###########################################################################
-- #  adoption_applications
-- ###########################################################################

-- PRIMARY KEY (id)   [created by CREATE TABLE in the migration]

CREATE INDEX fk_apps_attempt ON adoption_applications (eligibility_attempt_id, eligibility_quiz_type);

CREATE INDEX idx_apps_attempt ON adoption_applications (eligibility_attempt_id);

CREATE INDEX idx_apps_pet ON adoption_applications (pet_id);

-- The staff review queue: pending applications, oldest first.
CREATE INDEX idx_apps_queue ON adoption_applications (status, submitted_at);

CREATE INDEX idx_apps_reviewer ON adoption_applications (reviewed_by);

-- ENFORCES A RULE, NOT SPEED. The same trick: pending_flag is NULL unless
-- the row is PENDING, so a person may re-apply after a rejection but can
-- never have two applications open on the same pet at once.
CREATE UNIQUE INDEX uq_one_open_app ON adoption_applications (applicant_id, pet_id, pending_flag);

-- ###########################################################################
-- #  adoptions
-- ###########################################################################

-- PRIMARY KEY (id)   [created by CREATE TABLE in the migration]

CREATE INDEX idx_adoptions_adopter ON adoptions (adopter_id);

CREATE INDEX idx_adoptions_pet ON adoptions (pet_id);

CREATE INDEX idx_adoptions_shelter_date ON adoptions (shelter_id, adopted_on);

CREATE UNIQUE INDEX uq_adoption_application ON adoptions (application_id);

-- ###########################################################################
-- #  audit_log
-- ###########################################################################

-- PRIMARY KEY (id)   [created by CREATE TABLE in the migration]

CREATE INDEX idx_audit_actor ON audit_log (actor_id);

CREATE INDEX idx_audit_entity ON audit_log (entity_type, entity_id);

CREATE INDEX idx_audit_recent ON audit_log (created_at);

-- ###########################################################################
-- #  billing_slip_items
-- ###########################################################################

-- PRIMARY KEY (id)   [created by CREATE TABLE in the migration]

CREATE INDEX idx_slip_items ON billing_slip_items (slip_id, sort_order);

-- ###########################################################################
-- #  billing_slips
-- ###########################################################################

-- PRIMARY KEY (id)   [created by CREATE TABLE in the migration]

CREATE INDEX fk_slip_issuer ON billing_slips (issued_by);

-- An owner's own slips, newest first.
CREATE INDEX idx_slip_owner ON billing_slips (owner_id, issued_at);

-- Groups billing by shelter and date — what v_shelter_revenue reads.
CREATE INDEX idx_slip_shelter ON billing_slips (shelter_id, issued_at);

-- One billing slip per boarding stay, ever.
CREATE UNIQUE INDEX uq_slip_boarding ON billing_slips (boarding_request_id);

CREATE UNIQUE INDEX uq_slip_no ON billing_slips (slip_no);

-- One slip per subscription per month — makes the monthly billing job safe
-- to run twice.
CREATE UNIQUE INDEX uq_slip_period ON billing_slips (subscription_id, period_from);

-- ###########################################################################
-- #  boarding_charges
-- ###########################################################################

-- PRIMARY KEY (id)   [created by CREATE TABLE in the migration]

CREATE INDEX fk_charges_user ON boarding_charges (added_by);

CREATE INDEX idx_charges_boarding ON boarding_charges (boarding_id, added_at);

-- ###########################################################################
-- #  boarding_instructions
-- ###########################################################################

-- PRIMARY KEY (id)   [created by CREATE TABLE in the migration]

CREATE INDEX idx_instructions_boarding ON boarding_instructions (boarding_id);

-- ###########################################################################
-- #  boarding_requests
-- ###########################################################################

-- PRIMARY KEY (id)   [created by CREATE TABLE in the migration]

CREATE INDEX fk_boarding_attempt ON boarding_requests (quiz_attempt_id, quiz_attempt_type);

CREATE INDEX idx_boarding_attempt ON boarding_requests (quiz_attempt_id);

-- Serves the double-booking lock. KEEPS THE LOCK WINDOW SHORT — without it
-- the overlap check is a table scan HOLDING ROW LOCKS, which turns a
-- correctness feature into a throughput problem.
CREATE INDEX idx_boarding_overlap ON boarding_requests (pet_id, start_date, end_date);

CREATE INDEX idx_boarding_owner ON boarding_requests (owner_id);

CREATE INDEX idx_boarding_reviewer ON boarding_requests (reviewed_by);

-- The staff boarding board: this shelter, by status.
CREATE INDEX idx_boarding_shelter_status ON boarding_requests (shelter_id, status);

CREATE INDEX idx_boarding_suite ON boarding_requests (suite_id);

-- ###########################################################################
-- #  boarding_updates
-- ###########################################################################

-- PRIMARY KEY (id)   [created by CREATE TABLE in the migration]

CREATE INDEX idx_updates_author ON boarding_updates (posted_by);

CREATE INDEX idx_updates_boarding ON boarding_updates (boarding_id, posted_at);

-- ###########################################################################
-- #  care_logs
-- ###########################################################################

-- PRIMARY KEY (id)   [created by CREATE TABLE in the migration]

CREATE INDEX idx_logs_author ON care_logs (logged_by);

CREATE INDEX idx_logs_boarding ON care_logs (boarding_id);

CREATE INDEX idx_logs_pet_time ON care_logs (pet_id, logged_at);

CREATE INDEX idx_logs_task ON care_logs (task_id);

-- ###########################################################################
-- #  care_tasks
-- ###########################################################################

-- PRIMARY KEY (id)   [created by CREATE TABLE in the migration]

CREATE INDEX idx_care_assignee ON care_tasks (assigned_to);

CREATE INDEX idx_care_boarding ON care_tasks (boarding_id);

CREATE INDEX idx_care_completer ON care_tasks (completed_by);

CREATE INDEX idx_care_due ON care_tasks (status, due_at);

CREATE INDEX idx_care_pet ON care_tasks (pet_id);

-- ###########################################################################
-- #  daycare_attendance
-- ###########################################################################

-- PRIMARY KEY (id)   [created by CREATE TABLE in the migration]

CREATE INDEX fk_attendance_in_by ON daycare_attendance (checked_in_by);

CREATE INDEX fk_attendance_out_by ON daycare_attendance (checked_out_by);

CREATE INDEX fk_attendance_sub ON daycare_attendance (subscription_id, slot);

-- The daycare board for a given day.
CREATE INDEX idx_attendance_board ON daycare_attendance (visit_date, suite_id);

-- Who is still on site today (checked_out_at IS NULL).
CREATE INDEX idx_attendance_present ON daycare_attendance (visit_date, checked_out_at);

-- Answers "who is in this room, on this date, in this band, and still here"
-- in one lookup — the daycare capacity check.
CREATE INDEX idx_attendance_suite_band ON daycare_attendance (suite_id, visit_date, slot, checked_out_at);

-- One attendance row per subscription per day.
CREATE UNIQUE INDEX uq_visit_per_day ON daycare_attendance (subscription_id, visit_date);

-- ###########################################################################
-- #  daycare_subscriptions
-- ###########################################################################

-- PRIMARY KEY (id)   [created by CREATE TABLE in the migration]

CREATE INDEX fk_sub_attempt ON daycare_subscriptions (quiz_attempt_id, quiz_attempt_type);

CREATE INDEX idx_sub_billing ON daycare_subscriptions (status, start_date);

CREATE INDEX idx_sub_capacity ON daycare_subscriptions (shelter_id, slot, status);

CREATE INDEX idx_sub_owner ON daycare_subscriptions (owner_id, status);

CREATE INDEX idx_sub_pet ON daycare_subscriptions (pet_id);

CREATE UNIQUE INDEX uq_sub_id_slot ON daycare_subscriptions (id, slot);

-- ###########################################################################
-- #  favorites
-- ###########################################################################

-- PRIMARY KEY (user_id, pet_id)   [created by CREATE TABLE in the migration]

CREATE INDEX idx_favorites_pet ON favorites (pet_id);

-- ###########################################################################
-- #  health_reminders_sent
-- ###########################################################################

-- PRIMARY KEY (id)   [created by CREATE TABLE in the migration]

CREATE UNIQUE INDEX uq_reminder_once ON health_reminders_sent (vaccination_id, reminder_kind);

-- ###########################################################################
-- #  notifications
-- ###########################################################################

-- PRIMARY KEY (id)   [created by CREATE TABLE in the migration]

CREATE INDEX idx_notif_inbox ON notifications (user_id, read_at, created_at);

-- ###########################################################################
-- #  option_traits
-- ###########################################################################

-- PRIMARY KEY (option_id, trait_id)   [created by CREATE TABLE in the migration]

CREATE INDEX idx_option_traits_trait ON option_traits (trait_id);

-- ###########################################################################
-- #  pet_photos
-- ###########################################################################

-- PRIMARY KEY (id)   [created by CREATE TABLE in the migration]

CREATE INDEX idx_pet_photos_pet ON pet_photos (pet_id);

CREATE INDEX idx_pet_photos_uploader ON pet_photos (uploaded_by);

-- ENFORCES A RULE, NOT SPEED. At most one cover photo per pet. MySQL has no
-- partial index, so cover_flag is a generated column defined as IF(is_cover,
-- 1, NULL) — NULLs do not collide in a unique index, so a pet may have many
-- non-cover photos and exactly one cover.
CREATE UNIQUE INDEX uq_pet_cover ON pet_photos (pet_id, cover_flag);

-- ###########################################################################
-- #  pet_traits
-- ###########################################################################

-- PRIMARY KEY (pet_id, trait_id)   [created by CREATE TABLE in the migration]

CREATE INDEX idx_pet_traits_trait ON pet_traits (trait_id);

-- ###########################################################################
-- #  pets
-- ###########################################################################

-- PRIMARY KEY (id)   [created by CREATE TABLE in the migration]

CREATE INDEX fk_pets_created_by ON pets (created_by);

CREATE INDEX idx_pets_breed ON pets (breed);

-- The public catalogue. status FIRST because every catalogue query filters
-- on it; species and size are the filters the page offers next. A composite
-- index is only useful left to right, so this column order is a statement
-- about which filter comes first. Measured: with this index the browse query
-- is an index lookup reading only the matching rows; without it, a full
-- table scan of every pet.
CREATE INDEX idx_pets_browse ON pets (status, species, size);

-- The pets an adopter owns.
CREATE INDEX idx_pets_owner ON pets (owner_id);

-- A shelter's own pets.
CREATE INDEX idx_pets_shelter ON pets (shelter_id);

-- ###########################################################################
-- #  quiz_answers
-- ###########################################################################

-- PRIMARY KEY (id)   [created by CREATE TABLE in the migration]

CREATE INDEX idx_answers_option ON quiz_answers (option_id);

CREATE INDEX idx_answers_question ON quiz_answers (question_id);

CREATE UNIQUE INDEX uq_answer ON quiz_answers (attempt_id, question_id, option_id);

-- ###########################################################################
-- #  quiz_attempts
-- ###########################################################################

-- PRIMARY KEY (id)   [created by CREATE TABLE in the migration]

CREATE INDEX idx_attempts_user_type ON quiz_attempts (user_id, quiz_type, id);

CREATE UNIQUE INDEX uq_attempt_id_type ON quiz_attempts (id, quiz_type);

-- ###########################################################################
-- #  quiz_options
-- ###########################################################################

-- PRIMARY KEY (id)   [created by CREATE TABLE in the migration]

CREATE UNIQUE INDEX uq_option_order ON quiz_options (question_id, display_order);

-- ###########################################################################
-- #  quiz_questions
-- ###########################################################################

-- PRIMARY KEY (id)   [created by CREATE TABLE in the migration]

CREATE INDEX idx_questions_author ON quiz_questions (created_by);

CREATE INDEX idx_questions_bank ON quiz_questions (quiz_type, status, display_order);

-- ###########################################################################
-- #  reports
-- ###########################################################################

-- PRIMARY KEY (id)   [created by CREATE TABLE in the migration]

CREATE INDEX idx_reports_author ON reports (generated_by);

CREATE INDEX idx_reports_recent ON reports (generated_at);

CREATE INDEX idx_reports_shelter ON reports (shelter_id);

-- ###########################################################################
-- #  shelter_suites
-- ###########################################################################

-- PRIMARY KEY (id)   [created by CREATE TABLE in the migration]

CREATE UNIQUE INDEX uq_suite_per_shelter ON shelter_suites (shelter_id, name);

-- ###########################################################################
-- #  shelters
-- ###########################################################################

-- PRIMARY KEY (id)   [created by CREATE TABLE in the migration]

CREATE UNIQUE INDEX uq_shelters_name ON shelters (name);

-- ###########################################################################
-- #  traits
-- ###########################################################################

-- PRIMARY KEY (id)   [created by CREATE TABLE in the migration]

CREATE UNIQUE INDEX uq_traits_label ON traits (label);

CREATE UNIQUE INDEX uq_traits_slug ON traits (slug);

-- ###########################################################################
-- #  user_tokens
-- ###########################################################################

-- PRIMARY KEY (id)   [created by CREATE TABLE in the migration]

CREATE INDEX idx_user_tokens_expiry ON user_tokens (expires_at);

CREATE INDEX idx_user_tokens_user ON user_tokens (user_id, token_type);

CREATE UNIQUE INDEX uq_user_tokens_hash ON user_tokens (token_hash);

-- ###########################################################################
-- #  users
-- ###########################################################################

-- PRIMARY KEY (id)   [created by CREATE TABLE in the migration]

CREATE INDEX idx_users_shelter ON users (shelter_id);

-- Email is the natural key for an account.
CREATE UNIQUE INDEX uq_users_email ON users (email);

-- ###########################################################################
-- #  vaccinations
-- ###########################################################################

-- PRIMARY KEY (id)   [created by CREATE TABLE in the migration]

CREATE INDEX idx_vacc_due ON vaccinations (status, next_due_on);

CREATE INDEX idx_vacc_pet ON vaccinations (pet_id);

CREATE INDEX idx_vacc_recorder ON vaccinations (recorded_by);

CREATE INDEX idx_vacc_type ON vaccinations (vaccine_type_id);

-- ###########################################################################
-- #  vaccine_types
-- ###########################################################################

-- PRIMARY KEY (id)   [created by CREATE TABLE in the migration]

CREATE UNIQUE INDEX uq_vaccine_name ON vaccine_types (name);
