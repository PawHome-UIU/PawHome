-- =============================================================================
-- V6 -- the quiz type becomes part of the relationship, not a convention.
--
-- THE PROBLEM THIS FIXES
--
-- quiz_type is a discriminator. One bank of questions serves three different
-- quizzes: ELIGIBILITY gates an adoption application, BOARDING gates a
-- boarding request, and PERSONALITY feeds the matching engine.
--
-- But the two foreign keys that consume an attempt were type-blind:
--
--     adoption_applications.eligibility_attempt_id -> quiz_attempts (id)
--     boarding_requests.quiz_attempt_id            -> quiz_attempts (id)
--
-- They checked only that the attempt existed. Nothing stopped a PERSONALITY
-- attempt being accepted as somebody's eligibility check -- and the
-- personality quiz has no pass mark, so that is an adoption approved against
-- a quiz that cannot be failed. Precisely the class of rule this schema
-- claims to enforce itself rather than leave to the service layer.
--
-- THE FIX: MAKE THE TYPE PART OF THE KEY
--
-- quiz_attempts gains UNIQUE (id, quiz_type). On its own that is redundant --
-- id is already unique -- but it gives the child tables a two-column target.
-- Each child then carries a constant type column, pinned by a CHECK, and the
-- foreign key references the pair. Attaching the wrong kind of attempt now
-- fails at the database.
--
-- The added column is a CONSTANT, not a copy of another column's value. It
-- depends on nothing, so it introduces no transitive dependency and is not a
-- further departure from Third Normal Form.
--
-- WHAT IS DELIBERATELY NOT DONE HERE
--
-- The mirror rule -- only a PERSONALITY option may carry option_traits rows --
-- would require quiz_type to be carried down onto quiz_options, where it
-- WOULD be transitively dependent (option -> question -> quiz_type) and
-- therefore a genuine 3NF violation. Trading a real normalisation breach for
-- one more guard is not worth it, so that rule stays in the service layer.
-- This paragraph is its justification.
--
-- SECOND FIX: COLUMNS THAT WERE ASSERTING SOMETHING FALSE
--
-- score, max_score and passed were NOT NULL DEFAULT 0/FALSE. For ELIGIBILITY
-- and BOARDING they carry a real result. For PERSONALITY they do not exist as
-- concepts -- yet every personality attempt recorded passed = FALSE, which
-- does not read as "not applicable", it reads as "this person failed".
-- They are now nullable, and a CHECK makes the type decide: scored for the
-- two gate quizzes, null for the personality quiz. Neither state is optional.
-- =============================================================================


-- ---------------------------------------------------------------- the target
-- Referenced columns must be the leftmost columns of some index. id alone is
-- the primary key; this adds the (id, quiz_type) pair the children need.
ALTER TABLE quiz_attempts
    ADD UNIQUE KEY uq_attempt_id_type (id, quiz_type);


-- ------------------------------------------------- adoption -> ELIGIBILITY
ALTER TABLE adoption_applications
    DROP FOREIGN KEY fk_apps_attempt;

ALTER TABLE adoption_applications
    ADD COLUMN eligibility_quiz_type
        ENUM('ELIGIBILITY','PERSONALITY','BOARDING')
        NOT NULL DEFAULT 'ELIGIBILITY'
        COMMENT 'Constant. Exists only so the FK below can check the type.'
        AFTER eligibility_attempt_id;

ALTER TABLE adoption_applications
    ADD CONSTRAINT ck_apps_quiz_type
        CHECK (eligibility_quiz_type = 'ELIGIBILITY'),
    ADD CONSTRAINT fk_apps_attempt
        FOREIGN KEY (eligibility_attempt_id, eligibility_quiz_type)
        REFERENCES quiz_attempts (id, quiz_type)
        ON DELETE RESTRICT;


-- ---------------------------------------------------- boarding -> BOARDING
-- quiz_attempt_id is nullable here: a boarding request may predate the quiz.
-- A foreign key whose referencing columns include a NULL is satisfied without
-- a matching parent row, so the optional relationship survives unchanged.
ALTER TABLE boarding_requests
    DROP FOREIGN KEY fk_boarding_attempt;

ALTER TABLE boarding_requests
    ADD COLUMN quiz_attempt_type
        ENUM('ELIGIBILITY','PERSONALITY','BOARDING')
        NOT NULL DEFAULT 'BOARDING'
        COMMENT 'Constant. Exists only so the FK below can check the type.'
        AFTER quiz_attempt_id;

ALTER TABLE boarding_requests
    ADD CONSTRAINT ck_boarding_quiz_type
        CHECK (quiz_attempt_type = 'BOARDING'),
    ADD CONSTRAINT fk_boarding_attempt
        FOREIGN KEY (quiz_attempt_id, quiz_attempt_type)
        REFERENCES quiz_attempts (id, quiz_type)
        ON DELETE RESTRICT;


-- ------------------------------------------- a score, or an honest absence
-- MODIFY drops the old DEFAULT along with NOT NULL, which is the point: a
-- default of 0 is how "no score" quietly became "scored zero".
ALTER TABLE quiz_attempts
    MODIFY COLUMN score     SMALLINT NULL,
    MODIFY COLUMN max_score SMALLINT NULL,
    MODIFY COLUMN passed    BOOLEAN  NULL;

UPDATE quiz_attempts
   SET score = NULL, max_score = NULL, passed = NULL
 WHERE quiz_type = 'PERSONALITY';

-- Both directions. A gate quiz must be scored; a personality quiz must not
-- be. Leaving only the first half would still allow a personality attempt to
-- claim it passed.
ALTER TABLE quiz_attempts
    ADD CONSTRAINT ck_attempts_scored_by_type CHECK (
        (quiz_type = 'PERSONALITY'
             AND score IS NULL AND max_score IS NULL AND passed IS NULL)
        OR
        (quiz_type <> 'PERSONALITY'
             AND score IS NOT NULL AND max_score IS NOT NULL
             AND passed IS NOT NULL)
    );
