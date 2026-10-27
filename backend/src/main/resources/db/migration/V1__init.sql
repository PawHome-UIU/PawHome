-- =============================================================================
-- PawHome  ·  V1__init.sql
-- Pet Adoption & Boarding Management System
--
-- Engine   : MySQL 8.4 (InnoDB, utf8mb4)
-- Course   : CSE 3522 Database Management Systems Laboratory, UIU
--
-- 25 tables, 2 views. Derived from the completed Figma design: every table
-- here exists to serve a screen that was drawn before this file was written.
--
-- CONVENTIONS, and why:
--   BIGINT AUTO_INCREMENT   surrogate key on every non-junction table. SIGNED,
--                           not UNSIGNED -- an UNSIGNED parent key with a signed
--                           child column is MySQL errno 150 and a confusing
--                           hour. Signed BIGINT is ample at this scale.
--   DATETIME, never         MySQL has no timezone-aware type. TIMESTAMP silently
--   TIMESTAMP               converts by session timezone and overflows in 2038.
--                           Every instant below is stored in UTC by convention.
--   DECIMAL for money       Never FLOAT/DOUBLE.
--   ENGINE=InnoDB           Required for BOTH foreign keys and transactions.
--   CHECK constraints       Enforced from MySQL 8.0.16 only. On an older server
--                           they are PARSED AND SILENTLY IGNORED, which is worse
--                           than not having them -- pin the image to mysql:8.4.
-- =============================================================================

SET NAMES utf8mb4;
SET time_zone = '+00:00';


-- =============================================================================
-- MODULE 1 — IDENTITY & SHELTERS
-- =============================================================================

CREATE TABLE shelters (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    name                VARCHAR(120) NOT NULL,
    logo_path           VARCHAR(255)     NULL,
    email               VARCHAR(160) NOT NULL,
    phone               VARCHAR(30)  NOT NULL,
    address_line        VARCHAR(200) NOT NULL,
    city                VARCHAR(80)  NOT NULL,
    state               VARCHAR(80)  NOT NULL,
    zip                 VARCHAR(20)  NOT NULL,
    -- Denominator of the "28 / 50" capacity bar. The numerator is always a live
    -- COUNT of pets -- never stored, so it cannot drift.
    capacity            INT          NOT NULL,
    status              ENUM('ACTIVE','PENDING','INACTIVE') NOT NULL DEFAULT 'PENDING',
    description         TEXT             NULL,
    boarding_rate_daily DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_shelters_name (name),
    CONSTRAINT ck_shelters_capacity CHECK (capacity > 0),
    CONSTRAINT ck_shelters_rate     CHECK (boarding_rate_daily >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


CREATE TABLE users (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    full_name       VARCHAR(120) NOT NULL,
    email           VARCHAR(160) NOT NULL,
    password_hash   VARCHAR(100) NOT NULL,
    phone           VARCHAR(30)      NULL,
    role            ENUM('ADMIN','STAFF','ADOPTER') NOT NULL,
    -- Staff sign-ups insert PENDING and wait for an admin. Adopters are
    -- APPROVED on creation. Together with is_active this is the two-flag
    -- "enabled" rule: a login needs BOTH.
    approval_status ENUM('PENDING','APPROVED','REJECTED') NOT NULL DEFAULT 'APPROVED',
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    shelter_id      BIGINT           NULL,
    job_title       VARCHAR(80)      NULL,
    avatar_path     VARCHAR(255)     NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                                 ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_users_email (email),
    KEY idx_users_shelter (shelter_id),
    CONSTRAINT fk_users_shelter FOREIGN KEY (shelter_id)
        REFERENCES shelters (id) ON DELETE RESTRICT,
    -- A STAFF row must belong to a shelter; nobody else may.
    CONSTRAINT ck_users_staff_shelter CHECK (
        (role =  'STAFF' AND shelter_id IS NOT NULL) OR
        (role <> 'STAFF' AND shelter_id IS     NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


CREATE TABLE shelter_suites (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    shelter_id BIGINT      NOT NULL,
    name       VARCHAR(60) NOT NULL,          -- "Suite 4", "Kitty Loft"
    species    ENUM('DOG','CAT','OTHER') NULL,-- NULL = takes any species
    is_active  BOOLEAN     NOT NULL DEFAULT TRUE,

    PRIMARY KEY (id),
    UNIQUE KEY uq_suite_per_shelter (shelter_id, name),
    CONSTRAINT fk_suites_shelter FOREIGN KEY (shelter_id)
        REFERENCES shelters (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- =============================================================================
-- MODULE 2 — PETS, TRAITS & PHOTOS
-- =============================================================================

CREATE TABLE traits (
    id        BIGINT      NOT NULL AUTO_INCREMENT,
    label     VARCHAR(40) NOT NULL,
    -- Stable key: renaming a label must not break the matching engine.
    slug      VARCHAR(40) NOT NULL,
    is_active BOOLEAN     NOT NULL DEFAULT TRUE,

    PRIMARY KEY (id),
    UNIQUE KEY uq_traits_label (label),
    UNIQUE KEY uq_traits_slug  (slug)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


CREATE TABLE pets (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    -- Shelter of record. Does NOT change when the pet is adopted -- reporting
    -- must keep crediting the shelter that did the work.
    shelter_id      BIGINT       NOT NULL,
    name            VARCHAR(80)  NOT NULL,
    species         ENUM('DOG','CAT','OTHER') NOT NULL,
    breed           VARCHAR(100) NOT NULL,
    -- Number + unit, exactly as the Add Pet modal collects it. Shelters rarely
    -- know a rescue animal's birth date, and the modal does not ask for one.
    age_value       SMALLINT     NOT NULL,
    age_unit        ENUM('WEEKS','MONTHS','YEARS') NOT NULL,
    sex             ENUM('MALE','FEMALE') NOT NULL,
    size            ENUM('SMALL','MEDIUM','LARGE','GIANT') NOT NULL,
    weight_kg       DECIMAL(5,2)     NULL,
    status          ENUM('AVAILABLE','PENDING','ADOPTED','BOARDING','UNAVAILABLE')
                                 NOT NULL DEFAULT 'AVAILABLE',
    owner_id        BIGINT           NULL,
    description     TEXT             NULL,
    is_neutered     BOOLEAN      NOT NULL DEFAULT FALSE,
    is_microchipped BOOLEAN      NOT NULL DEFAULT FALSE,
    adoption_fee    DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    view_count      INT          NOT NULL DEFAULT 0,
    created_by      BIGINT       NOT NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                                 ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    KEY idx_pets_shelter (shelter_id),
    KEY idx_pets_owner   (owner_id),
    KEY idx_pets_breed   (breed),
    -- The browse page filters on exactly these three columns and reports
    -- "248 pets found". Without this index every filter change is a full scan.
    KEY idx_pets_browse  (status, species, size),

    CONSTRAINT fk_pets_shelter FOREIGN KEY (shelter_id)
        REFERENCES shelters (id) ON DELETE RESTRICT,
    CONSTRAINT fk_pets_owner FOREIGN KEY (owner_id)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_pets_created_by FOREIGN KEY (created_by)
        REFERENCES users (id) ON DELETE RESTRICT,

    CONSTRAINT ck_pets_age    CHECK (age_value > 0),
    CONSTRAINT ck_pets_weight CHECK (weight_kg IS NULL OR weight_kg > 0),
    CONSTRAINT ck_pets_fee    CHECK (adoption_fee >= 0),
    -- A placed pet has an owner; an unplaced pet has none. Neither half of that
    -- can drift, because both are the same constraint.
    CONSTRAINT ck_pets_owner_when_placed CHECK (
        (status IN ('ADOPTED','BOARDING') AND owner_id IS NOT NULL) OR
        (status NOT IN ('ADOPTED','BOARDING') AND owner_id IS NULL)
    )
    -- NOTE: is_vaccinated is deliberately absent. It is derived in
    -- v_pet_listing from the vaccination records. Storing a third copy is how
    -- a pet ends up showing a green tick beside an overdue shot.
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


CREATE TABLE pet_traits (
    pet_id   BIGINT NOT NULL,
    trait_id BIGINT NOT NULL,

    PRIMARY KEY (pet_id, trait_id),
    KEY idx_pet_traits_trait (trait_id),
    CONSTRAINT fk_pet_traits_pet FOREIGN KEY (pet_id)
        REFERENCES pets (id) ON DELETE CASCADE,
    CONSTRAINT fk_pet_traits_trait FOREIGN KEY (trait_id)
        REFERENCES traits (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


CREATE TABLE pet_photos (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    pet_id      BIGINT       NOT NULL,
    file_path   VARCHAR(255) NOT NULL,
    is_cover    BOOLEAN      NOT NULL DEFAULT FALSE,
    -- MySQL has no partial indexes, so "at most one cover photo per pet" is
    -- enforced with a generated column: 1 for a cover row, NULL otherwise.
    -- A MySQL UNIQUE index ignores rows where any key part is NULL, so every
    -- non-cover photo is exempt while (pet_id, 1) can appear only once.
    --
    -- NOTE the shape of this expression. The obvious version --
    --     cover_key BIGINT AS (IF(is_cover, pet_id, NULL)) STORED
    -- with UNIQUE (cover_key) -- is rejected at CREATE TABLE time with
    -- errno 1215. MySQL forbids ON DELETE CASCADE on a column that a stored
    -- generated column reads, and that version reads pet_id. Deriving from
    -- is_cover alone and putting pet_id in the index instead enforces exactly
    -- the same rule while leaving the foreign key free to cascade.
    cover_flag  TINYINT AS (IF(is_cover, 1, NULL)) STORED,
    sort_order  SMALLINT     NOT NULL DEFAULT 0,
    uploaded_by BIGINT       NOT NULL,
    uploaded_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_pet_cover (pet_id, cover_flag),
    KEY idx_pet_photos_pet (pet_id),
    KEY idx_pet_photos_uploader (uploaded_by),
    CONSTRAINT fk_pet_photos_pet FOREIGN KEY (pet_id)
        REFERENCES pets (id) ON DELETE CASCADE,
    CONSTRAINT fk_pet_photos_user FOREIGN KEY (uploaded_by)
        REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- The heart icon. The one place in this system where a hard DELETE is correct:
-- a removed favourite has no historical value.
CREATE TABLE favorites (
    user_id    BIGINT   NOT NULL,
    pet_id     BIGINT   NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (user_id, pet_id),
    KEY idx_favorites_pet (pet_id),
    CONSTRAINT fk_favorites_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_favorites_pet FOREIGN KEY (pet_id)
        REFERENCES pets (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- =============================================================================
-- MODULE 3 — QUIZ & MATCHING
-- =============================================================================

CREATE TABLE quiz_questions (
    id            BIGINT NOT NULL AUTO_INCREMENT,
    -- One bank, three quizzes. BOARDING questions are asked when an owner
    -- requests a stay; the admin authors all three in the same modal.
    quiz_type     ENUM('ELIGIBILITY','PERSONALITY','BOARDING') NOT NULL,
    question_text TEXT   NOT NULL,
    answer_type   ENUM('YES_NO','SINGLE_CHOICE','MULTI_CHOICE','SCALE') NOT NULL,
    category      ENUM('ENVIRONMENT','LIFESTYLE','HISTORY','COMMITMENT','HEALTH') NOT NULL,
    -- Never hard-delete a question: quiz_answers reference it forever.
    status        ENUM('DRAFT','ACTIVE','ARCHIVED') NOT NULL DEFAULT 'DRAFT',
    is_required   BOOLEAN NOT NULL DEFAULT TRUE,
    display_order INT    NOT NULL DEFAULT 0,
    created_by    BIGINT NOT NULL,
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    KEY idx_questions_bank (quiz_type, status, display_order),
    KEY idx_questions_author (created_by),
    CONSTRAINT fk_questions_author FOREIGN KEY (created_by)
        REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


CREATE TABLE quiz_options (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    question_id   BIGINT       NOT NULL,
    label         VARCHAR(120) NOT NULL,
    -- The "W:" field in the Add Question modal. Sum of the weights of the
    -- chosen options is the eligibility score.
    weight        SMALLINT     NOT NULL DEFAULT 0,
    -- TRUE opens a free-text follow-up ("Custom" -> what is the schedule?).
    -- A flag rather than hard-coded columns, so a new question costs no
    -- migration.
    requires_text BOOLEAN      NOT NULL DEFAULT FALSE,
    display_order INT          NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    UNIQUE KEY uq_option_order (question_id, display_order),
    CONSTRAINT fk_options_question FOREIGN KEY (question_id)
        REFERENCES quiz_questions (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- The bridge between an adopter's answer and a pet's chips. Without this table
-- the match score has nothing to compute on.
CREATE TABLE option_traits (
    option_id BIGINT NOT NULL,
    trait_id  BIGINT NOT NULL,

    PRIMARY KEY (option_id, trait_id),
    KEY idx_option_traits_trait (trait_id),
    CONSTRAINT fk_option_traits_option FOREIGN KEY (option_id)
        REFERENCES quiz_options (id) ON DELETE CASCADE,
    CONSTRAINT fk_option_traits_trait FOREIGN KEY (trait_id)
        REFERENCES traits (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


CREATE TABLE quiz_attempts (
    id           BIGINT   NOT NULL AUTO_INCREMENT,
    user_id      BIGINT   NOT NULL,
    quiz_type    ENUM('ELIGIBILITY','PERSONALITY','BOARDING') NOT NULL,
    score        SMALLINT NOT NULL DEFAULT 0,
    max_score    SMALLINT NOT NULL DEFAULT 0,
    passed       BOOLEAN  NOT NULL DEFAULT FALSE,
    submitted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    -- "Retake Quiz" appends an attempt rather than overwriting, so the history
    -- survives. This index finds a user's latest attempt of a given type.
    KEY idx_attempts_user_type (user_id, quiz_type, id),
    CONSTRAINT fk_attempts_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_attempts_score CHECK (score >= 0 AND score <= max_score)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- One row per SELECTED OPTION -- which is what makes MULTI_CHOICE work without
-- a second table or a delimited string.
CREATE TABLE quiz_answers (
    id          BIGINT   NOT NULL AUTO_INCREMENT,
    attempt_id  BIGINT   NOT NULL,
    question_id BIGINT   NOT NULL,
    option_id   BIGINT       NULL,   -- NULL for SCALE answers
    answer_text TEXT         NULL,   -- the requires_text follow-up
    scale_value SMALLINT     NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uq_answer (attempt_id, question_id, option_id),
    KEY idx_answers_question (question_id),
    KEY idx_answers_option (option_id),
    CONSTRAINT fk_answers_attempt FOREIGN KEY (attempt_id)
        REFERENCES quiz_attempts (id) ON DELETE CASCADE,
    CONSTRAINT fk_answers_question FOREIGN KEY (question_id)
        REFERENCES quiz_questions (id) ON DELETE RESTRICT,
    CONSTRAINT fk_answers_option FOREIGN KEY (option_id)
        REFERENCES quiz_options (id) ON DELETE RESTRICT,
    CONSTRAINT ck_answers_scale CHECK (scale_value IS NULL OR scale_value BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- =============================================================================
-- MODULE 4 — ADOPTION
-- =============================================================================

CREATE TABLE adoption_applications (
    id                     BIGINT NOT NULL AUTO_INCREMENT,
    pet_id                 BIGINT NOT NULL,
    applicant_id           BIGINT NOT NULL,
    eligibility_attempt_id BIGINT NOT NULL,

    -- ---- DELIBERATE 3NF EXCEPTIONS (justified) --------------------------
    -- quiz_score is derivable from the attempt, and the applicant block is
    -- derivable from users. Both are frozen here on purpose: an application is
    -- a document a staff member reviewed and signed off. If the adopter later
    -- moves house, or an admin re-weights a quiz option, last year's approved
    -- application must still read exactly as it read when it was approved.
    -- Normalising these would silently rewrite a reviewed record.
    quiz_score             SMALLINT     NOT NULL,
    applicant_name         VARCHAR(120) NOT NULL,
    applicant_email        VARCHAR(160) NOT NULL,
    applicant_phone        VARCHAR(30)  NOT NULL,
    applicant_address      VARCHAR(200) NOT NULL,
    applicant_city         VARCHAR(80)  NOT NULL,
    -- ---------------------------------------------------------------------

    home_type              VARCHAR(60)  NOT NULL,   -- "Single Family House"
    time_at_home           VARCHAR(60)  NOT NULL,   -- "Full-time / WFH"
    pet_experience         VARCHAR(200) NOT NULL,
    household_note         VARCHAR(200)     NULL,   -- "No other pets, 1 child age 8"

    status                 ENUM('PENDING','APPROVED','REJECTED','WITHDRAWN')
                                        NOT NULL DEFAULT 'PENDING',
    -- Same technique as pet_photos.cover_flag: 1 while the row is pending,
    -- NULL once it is decided. With (applicant_id, pet_id) in the index this
    -- means "one OPEN application per person per pet" -- a decided application
    -- drops out of the index, so the same person may apply again later, and
    -- other people may apply for the same pet at any time.
    pending_flag           TINYINT AS (IF(status = 'PENDING', 1, NULL)) STORED,

    submitted_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_by            BIGINT       NULL,
    reviewed_at            DATETIME     NULL,
    decline_reason_code    ENUM('INCOMPLETE_INFO','HOUSING_UNSUITABLE',
                                'INSUFFICIENT_EXPERIENCE','PET_ALREADY_MATCHED',
                                'FAILED_ELIGIBILITY','OTHER') NULL,
    decline_message        TEXT         NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uq_one_open_app (applicant_id, pet_id, pending_flag),
    KEY idx_apps_pet (pet_id),
    KEY idx_apps_reviewer (reviewed_by),
    KEY idx_apps_attempt (eligibility_attempt_id),
    -- The staff review queue: pending first, newest first.
    KEY idx_apps_queue (status, submitted_at),

    CONSTRAINT fk_apps_pet FOREIGN KEY (pet_id)
        REFERENCES pets (id) ON DELETE RESTRICT,
    CONSTRAINT fk_apps_applicant FOREIGN KEY (applicant_id)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_apps_attempt FOREIGN KEY (eligibility_attempt_id)
        REFERENCES quiz_attempts (id) ON DELETE RESTRICT,
    CONSTRAINT fk_apps_reviewer FOREIGN KEY (reviewed_by)
        REFERENCES users (id) ON DELETE RESTRICT,

    CONSTRAINT ck_apps_score CHECK (quiz_score >= 0),
    -- The Decline modal makes both fields mandatory, and the message is shown
    -- to the applicant. The database says so too.
    CONSTRAINT ck_apps_decline CHECK (
        status <> 'REJECTED' OR
        (decline_reason_code IS NOT NULL AND decline_message IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


CREATE TABLE adoptions (
    id             BIGINT NOT NULL AUTO_INCREMENT,
    application_id BIGINT NOT NULL,
    pet_id         BIGINT NOT NULL,
    adopter_id     BIGINT NOT NULL,
    -- 3NF EXCEPTION (justified): reachable via pet_id -> pets.shelter_id.
    -- Copied so "Adoptions by Shelter" keeps crediting the shelter that
    -- performed the adoption, even if the pet is later transferred.
    shelter_id     BIGINT NOT NULL,
    adopted_on     DATE   NOT NULL,
    fee_paid       DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    -- One adoption per application, enforced by the database rather than by
    -- hoping the service layer is never called twice.
    UNIQUE KEY uq_adoption_application (application_id),
    KEY idx_adoptions_pet (pet_id),
    KEY idx_adoptions_adopter (adopter_id),
    KEY idx_adoptions_shelter_date (shelter_id, adopted_on),

    CONSTRAINT fk_adoptions_application FOREIGN KEY (application_id)
        REFERENCES adoption_applications (id) ON DELETE RESTRICT,
    CONSTRAINT fk_adoptions_pet FOREIGN KEY (pet_id)
        REFERENCES pets (id) ON DELETE RESTRICT,
    CONSTRAINT fk_adoptions_adopter FOREIGN KEY (adopter_id)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_adoptions_shelter FOREIGN KEY (shelter_id)
        REFERENCES shelters (id) ON DELETE RESTRICT,
    CONSTRAINT ck_adoptions_fee CHECK (fee_paid >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- =============================================================================
-- MODULE 5 — BOARDING
-- =============================================================================

CREATE TABLE boarding_requests (
    id                  BIGINT NOT NULL AUTO_INCREMENT,
    pet_id              BIGINT NOT NULL,
    -- Must equal pets.owner_id at request time. Checked in the service, because
    -- a CHECK constraint cannot read another table.
    owner_id            BIGINT NOT NULL,
    shelter_id          BIGINT NOT NULL,
    suite_id            BIGINT     NULL,   -- assigned when staff accept
    quiz_attempt_id     BIGINT     NULL,   -- the boarding quiz answers
    start_date          DATE   NOT NULL,
    end_date            DATE   NOT NULL,
    status              ENUM('REQUESTED','ACCEPTED','ACTIVE','COMPLETED',
                             'DECLINED','CANCELLED') NOT NULL DEFAULT 'REQUESTED',
    emergency_contact   VARCHAR(160) NOT NULL,
    -- Its own column rather than buried in quiz answers: staff need this at a
    -- glance in an emergency, not after a three-table join.
    vet_care_authorised BOOLEAN NOT NULL DEFAULT FALSE,
    -- 3NF EXCEPTION (justified): copied from shelters.boarding_rate_daily at
    -- request time so a price quoted to a customer cannot change retroactively.
    daily_rate          DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    estimated_cost      DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    requested_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_by         BIGINT     NULL,
    reviewed_at         DATETIME   NULL,
    decline_message     TEXT       NULL,

    PRIMARY KEY (id),
    -- ** THE INDEX TO WRITE UP FOR THE COURSE. **
    -- The date-overlap check runs on every accept, INSIDE a transaction holding
    -- SELECT ... FOR UPDATE row locks. This index shortens the lock window, not
    -- merely the query -- which is why it earns its place. See the note below.
    KEY idx_boarding_overlap (pet_id, start_date, end_date),
    KEY idx_boarding_shelter_status (shelter_id, status),
    KEY idx_boarding_owner (owner_id),
    KEY idx_boarding_suite (suite_id),
    KEY idx_boarding_attempt (quiz_attempt_id),
    KEY idx_boarding_reviewer (reviewed_by),

    CONSTRAINT fk_boarding_pet FOREIGN KEY (pet_id)
        REFERENCES pets (id) ON DELETE RESTRICT,
    CONSTRAINT fk_boarding_owner FOREIGN KEY (owner_id)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_boarding_shelter FOREIGN KEY (shelter_id)
        REFERENCES shelters (id) ON DELETE RESTRICT,
    CONSTRAINT fk_boarding_suite FOREIGN KEY (suite_id)
        REFERENCES shelter_suites (id) ON DELETE RESTRICT,
    CONSTRAINT fk_boarding_attempt FOREIGN KEY (quiz_attempt_id)
        REFERENCES quiz_attempts (id) ON DELETE RESTRICT,
    CONSTRAINT fk_boarding_reviewer FOREIGN KEY (reviewed_by)
        REFERENCES users (id) ON DELETE RESTRICT,

    CONSTRAINT ck_boarding_dates CHECK (end_date > start_date),
    CONSTRAINT ck_boarding_cost  CHECK (daily_rate >= 0 AND estimated_cost >= 0)

    -- NO-DOUBLE-BOOKING IS NOT A CONSTRAINT HERE, AND CANNOT BE.
    -- PostgreSQL would say it declaratively:
    --     EXCLUDE USING gist (pet_id WITH =, daterange(start,end) WITH &&)
    --                  WHERE (status IN ('ACCEPTED','ACTIVE'))
    -- MySQL has no equivalent, so the guarantee moves into a transaction:
    --     START TRANSACTION;
    --     SELECT id FROM boarding_requests
    --      WHERE pet_id = ? AND status IN ('ACCEPTED','ACTIVE')
    --        AND start_date <= ? AND end_date >= ?      -- overlap test
    --      FOR UPDATE;                                  -- locks the range
    --     -- any row returned -> ROLLBACK and decline
    --     UPDATE boarding_requests SET status='ACCEPTED', suite_id=? WHERE id=?;
    --     COMMIT;
    -- An application-level check without FOR UPDATE lets two concurrent
    -- accepts both pass. The lock is what makes it correct.
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- Categorised in the design (Dietary needs / Comfort & play), so these are rows
-- rather than one blob of text: adding a category never means changing a page.
CREATE TABLE boarding_instructions (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    boarding_id BIGINT NOT NULL,
    category    ENUM('DIETARY','MEDICATION','COMFORT_PLAY','EXERCISE','OTHER') NOT NULL,
    body        TEXT   NOT NULL,

    PRIMARY KEY (id),
    KEY idx_instructions_boarding (boarding_id),
    CONSTRAINT fk_instructions_boarding FOREIGN KEY (boarding_id)
        REFERENCES boarding_requests (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


CREATE TABLE boarding_updates (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    boarding_id BIGINT NOT NULL,
    update_type ENUM('ARRIVAL_CHECK_IN','FEEDING','PLAYTIME','MEDICATION',
                     'HEALTH','DEPARTURE') NOT NULL,
    note        TEXT   NOT NULL,
    photo_path  VARCHAR(255) NULL,
    posted_by   BIGINT NOT NULL,
    posted_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    KEY idx_updates_boarding (boarding_id, posted_at),
    KEY idx_updates_author (posted_by),
    CONSTRAINT fk_updates_boarding FOREIGN KEY (boarding_id)
        REFERENCES boarding_requests (id) ON DELETE CASCADE,
    CONSTRAINT fk_updates_author FOREIGN KEY (posted_by)
        REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- =============================================================================
-- MODULE 6 — CARE & HEALTH
--
-- Two tables, not one. A scheduled task has a due time, an assignee and a
-- done/not-done state; a log has an actual time, an author and a note. They
-- have different lifecycles -- a task is updated, a log is never touched again
-- -- and keeping them apart is what lets you see a task marked done three hours
-- late instead of quietly on time.
-- =============================================================================

CREATE TABLE care_tasks (
    id           BIGINT NOT NULL AUTO_INCREMENT,
    pet_id       BIGINT NOT NULL,
    boarding_id  BIGINT     NULL,   -- NULL for a shelter resident
    task_type    ENUM('FEEDING','MEDICATION','EXERCISE','GROOMING','WOUND_CARE') NOT NULL,
    description  VARCHAR(200) NOT NULL,
    due_at       DATETIME NOT NULL,
    priority     ENUM('NORMAL','CRITICAL') NOT NULL DEFAULT 'NORMAL',
    -- OVERDUE is NOT a status. It is status='PENDING' AND due_at < NOW().
    -- Storing it would need a job to keep it true.
    status       ENUM('PENDING','DONE','SKIPPED') NOT NULL DEFAULT 'PENDING',
    assigned_to  BIGINT     NULL,
    completed_by BIGINT     NULL,
    completed_at DATETIME   NULL,

    PRIMARY KEY (id),
    KEY idx_care_due (status, due_at),
    KEY idx_care_pet (pet_id),
    KEY idx_care_boarding (boarding_id),
    KEY idx_care_assignee (assigned_to),
    KEY idx_care_completer (completed_by),
    CONSTRAINT fk_tasks_pet FOREIGN KEY (pet_id)
        REFERENCES pets (id) ON DELETE CASCADE,
    CONSTRAINT fk_tasks_boarding FOREIGN KEY (boarding_id)
        REFERENCES boarding_requests (id) ON DELETE CASCADE,
    CONSTRAINT fk_tasks_assignee FOREIGN KEY (assigned_to)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_tasks_completer FOREIGN KEY (completed_by)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_tasks_done CHECK (
        status <> 'DONE' OR (completed_by IS NOT NULL AND completed_at IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- Append-only. Never updated, never deleted.
CREATE TABLE care_logs (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    pet_id      BIGINT NOT NULL,
    boarding_id BIGINT     NULL,
    task_id     BIGINT     NULL,   -- NULL for an ad-hoc "Log Care Task"
    task_type   ENUM('FEEDING','MEDICATION','EXERCISE','GROOMING','WOUND_CARE') NOT NULL,
    notes       TEXT   NOT NULL,
    logged_by   BIGINT NOT NULL,
    logged_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    KEY idx_logs_pet_time (pet_id, logged_at),
    KEY idx_logs_boarding (boarding_id),
    KEY idx_logs_task (task_id),
    KEY idx_logs_author (logged_by),
    CONSTRAINT fk_logs_pet FOREIGN KEY (pet_id)
        REFERENCES pets (id) ON DELETE CASCADE,
    CONSTRAINT fk_logs_boarding FOREIGN KEY (boarding_id)
        REFERENCES boarding_requests (id) ON DELETE CASCADE,
    CONSTRAINT fk_logs_task FOREIGN KEY (task_id)
        REFERENCES care_tasks (id) ON DELETE SET NULL,
    CONSTRAINT fk_logs_author FOREIGN KEY (logged_by)
        REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


CREATE TABLE vaccine_types (
    id        BIGINT      NOT NULL AUTO_INCREMENT,
    name      VARCHAR(80) NOT NULL,
    species   ENUM('DOG','CAT','OTHER') NULL,  -- NULL = applies to all
    is_active BOOLEAN     NOT NULL DEFAULT TRUE,

    PRIMARY KEY (id),
    UNIQUE KEY uq_vaccine_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


CREATE TABLE vaccinations (
    id               BIGINT NOT NULL AUTO_INCREMENT,
    pet_id           BIGINT NOT NULL,
    vaccine_type_id  BIGINT NOT NULL,
    administered_on  DATE   NOT NULL,
    -- "Overdue Vaccinations (3)" is a query on this column, not a stored flag.
    next_due_on      DATE       NULL,
    -- Free text: the vet who gave the shot is usually not a system user.
    administered_by  VARCHAR(120) NOT NULL,
    batch_lot        VARCHAR(60)  NULL,
    status           ENUM('COMPLETED','SCHEDULED') NOT NULL DEFAULT 'COMPLETED',
    certificate_path VARCHAR(255) NULL,
    notes            TEXT         NULL,
    recorded_by      BIGINT NOT NULL,
    created_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    KEY idx_vacc_pet (pet_id),
    KEY idx_vacc_due (status, next_due_on),
    KEY idx_vacc_type (vaccine_type_id),
    KEY idx_vacc_recorder (recorded_by),
    CONSTRAINT fk_vacc_pet FOREIGN KEY (pet_id)
        REFERENCES pets (id) ON DELETE CASCADE,
    CONSTRAINT fk_vacc_type FOREIGN KEY (vaccine_type_id)
        REFERENCES vaccine_types (id) ON DELETE RESTRICT,
    CONSTRAINT fk_vacc_recorder FOREIGN KEY (recorded_by)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_vacc_next_due CHECK (next_due_on IS NULL OR next_due_on > administered_on)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- =============================================================================
-- MODULE 7 — PLATFORM
-- =============================================================================

-- Powers "Recent Activity" on the admin dashboard. Append-only; the actor is
-- read from the security context, never passed in by the caller.
CREATE TABLE audit_log (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    actor_id    BIGINT     NULL,   -- NULL for system actions
    action      VARCHAR(60) NOT NULL,
    entity_type VARCHAR(40) NOT NULL,
    entity_id   BIGINT NOT NULL,
    detail      TEXT       NULL,
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    KEY idx_audit_recent (created_at),
    KEY idx_audit_entity (entity_type, entity_id),
    KEY idx_audit_actor (actor_id),
    CONSTRAINT fk_audit_actor FOREIGN KEY (actor_id)
        REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


CREATE TABLE reports (
    id           BIGINT NOT NULL AUTO_INCREMENT,
    file_name    VARCHAR(160) NOT NULL,
    file_path    VARCHAR(255) NOT NULL,
    report_type  ENUM('ADOPTION_SUMMARY','SHELTER_EFFICIENCY','PET_INVENTORY',
                      'QUARTERLY_IMPACT') NOT NULL,
    date_from    DATE NOT NULL,
    date_to      DATE NOT NULL,
    shelter_id   BIGINT NULL,   -- NULL = all shelters
    generated_by BIGINT NULL,   -- NULL = "Generated by System"
    generated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    KEY idx_reports_recent (generated_at),
    KEY idx_reports_shelter (shelter_id),
    KEY idx_reports_author (generated_by),
    CONSTRAINT fk_reports_shelter FOREIGN KEY (shelter_id)
        REFERENCES shelters (id) ON DELETE SET NULL,
    CONSTRAINT fk_reports_author FOREIGN KEY (generated_by)
        REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_reports_range CHECK (date_to >= date_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


CREATE TABLE notifications (
    id         BIGINT NOT NULL AUTO_INCREMENT,
    user_id    BIGINT NOT NULL,
    type       ENUM('APPLICATION_SUBMITTED','APPLICATION_DECIDED','BOARDING_DECIDED',
                    'BOARDING_UPDATE','STAFF_APPROVAL','CARE_TASK_DUE') NOT NULL,
    title      VARCHAR(160) NOT NULL,
    body       TEXT       NULL,
    link_url   VARCHAR(255) NULL,
    read_at    DATETIME   NULL,   -- NULL = unread; drives the bell's dot
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    KEY idx_notif_inbox (user_id, read_at, created_at),
    CONSTRAINT fk_notif_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- =============================================================================
-- VIEWS
-- =============================================================================

-- The single reusable listing projection, consumed by FOUR pages: guest browse,
-- adopter browse, favourites, and staff Manage Pets. One definition means the
-- vaccination rule cannot drift between the guest page and the staff page.
--
-- is_vaccinated is DERIVED here rather than stored on pets: at least one
-- completed shot, and nothing scheduled that is already past due.
CREATE OR REPLACE VIEW v_pet_listing AS
SELECT
    p.id            AS pet_id,
    p.name          AS pet_name,
    p.species,
    p.breed,
    p.age_value,
    p.age_unit,
    p.sex,
    p.size,
    p.status,
    p.adoption_fee,
    p.owner_id,
    p.view_count,
    p.created_at,
    s.id            AS shelter_id,
    s.name          AS shelter_name,
    s.city          AS shelter_city,
    (SELECT ph.file_path
       FROM pet_photos ph
      WHERE ph.pet_id = p.id AND ph.is_cover = TRUE
      LIMIT 1)                                        AS cover_photo_path,
    (SELECT GROUP_CONCAT(t.label ORDER BY t.label SEPARATOR ', ')
       FROM pet_traits pt
       JOIN traits t ON t.id = pt.trait_id
      WHERE pt.pet_id = p.id)                         AS trait_labels,
    (SELECT COUNT(*)
       FROM adoption_applications a
      WHERE a.pet_id = p.id AND a.status = 'PENDING') AS open_application_count,
    (    EXISTS (SELECT 1 FROM vaccinations v
                  WHERE v.pet_id = p.id AND v.status = 'COMPLETED')
     AND NOT EXISTS (SELECT 1 FROM vaccinations v2
                      WHERE v2.pet_id = p.id
                        AND v2.status = 'SCHEDULED'
                        AND v2.next_due_on < CURDATE())
    )                                                 AS is_vaccinated
FROM pets p
JOIN shelters s ON s.id = p.shelter_id;


-- The "YOUR TRAITS" chip row on the Recommendations page, and the adopter half
-- of the match score.
--
-- A VIEW and not a table on purpose: this is fully derived from data already
-- stored, so materialising it would be a 3NF violation whose only argument is
-- speed -- and the personality quiz is taken rarely enough that speed is not a
-- problem. It also satisfies the course's reusable-VIEW requirement.
CREATE OR REPLACE VIEW v_adopter_traits AS
SELECT
    qa.user_id,
    t.id    AS trait_id,
    t.label AS trait_label
FROM quiz_attempts qa
JOIN quiz_answers  ans ON ans.attempt_id = qa.id
JOIN option_traits ot  ON ot.option_id   = ans.option_id
JOIN traits        t   ON t.id           = ot.trait_id
WHERE qa.quiz_type = 'PERSONALITY'
  -- correlated: only the user's most recent personality attempt counts
  AND qa.id = (SELECT MAX(qa2.id)
                 FROM quiz_attempts qa2
                WHERE qa2.user_id   = qa.user_id
                  AND qa2.quiz_type = 'PERSONALITY')
GROUP BY qa.user_id, t.id, t.label;
