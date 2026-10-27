-- =============================================================================
-- PawHome  ·  V2__seed_reference_data.sql
--
-- Reference data the application cannot function without, plus one bootstrap
-- administrator. This is NOT demo content -- no pets, no shelters, no fake
-- adoptions. Those belong in a separate, optional demo migration so that a
-- production deploy of this schema is not polluted with test animals.
--
-- Everything here is idempotent-safe on a fresh database. Flyway runs a
-- migration exactly once, so re-running is not a concern, but INSERT IGNORE
-- keeps a manual re-run from throwing.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. TRAIT VOCABULARY
--
-- Exactly the seven chips the Add Pet modal offers, and nothing else. Both
-- sides of the matching engine speak this vocabulary: staff tag a pet with
-- these, and personality quiz options map onto these through option_traits.
-- Adding an eighth trait later costs one row here, not a migration.
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO traits (label, slug) VALUES
    ('Friendly',     'friendly'),
    ('Playful',      'playful'),
    ('Calm',         'calm'),
    ('Shy',          'shy'),
    ('Energetic',    'energetic'),
    ('Affectionate', 'affectionate'),
    ('Independent',  'independent');


-- -----------------------------------------------------------------------------
-- 2. VACCINE CATALOGUE
--
-- Backs the "Select a vaccine..." dropdown in the Add Vaccination modal.
-- species NULL means the vaccine applies to any animal.
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO vaccine_types (name, species) VALUES
    ('Rabies',                    NULL),
    ('DHPP (Distemper/Parvo)',    'DOG'),
    ('Bordetella',                'DOG'),
    ('Canine Influenza',          'DOG'),
    ('Leptospirosis',             'DOG'),
    ('FVRCP',                     'CAT'),
    ('Feline Leukemia (FeLV)',    'CAT'),
    ('Deworming',                 NULL);


-- -----------------------------------------------------------------------------
-- 3. BOOTSTRAP ADMINISTRATOR
--
-- Someone has to create the first shelter and approve the first staff account,
-- and no screen can do that without an admin already existing.
--
-- Credentials:  admin@pawhome.local  /  admin123
--
-- ** CHANGE THIS PASSWORD BEFORE ANY DEMO. ** The hash below is BCrypt cost 10
-- of a password that is now in your git history. It exists so a fresh clone
-- boots with a usable login and zero setup, exactly like the dev JWT secret in
-- application.yaml.
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO users (full_name, email, password_hash, role, approval_status, is_active)
VALUES ('PawHome Administrator',
        'admin@pawhome.local',
        '$2b$10$3FdzBhbcG8yo4hLF9yosuOHu5I2zPKJsWFzqqObxa4eCFM3.YWflG',
        'ADMIN', 'APPROVED', TRUE);


-- -----------------------------------------------------------------------------
-- 4. ELIGIBILITY QUIZ  (5 questions, transcribed from the design)
--
-- Scoring is the sum of the chosen options' weights. The pass mark is applied
-- in the service, not here, so an admin can retune it without a migration.
-- -----------------------------------------------------------------------------
SET @admin := (SELECT id FROM users WHERE email = 'admin@pawhome.local');

INSERT INTO quiz_questions
    (quiz_type, question_text, answer_type, category, status, is_required, display_order, created_by)
VALUES
    ('ELIGIBILITY','Have you cared for a pet before?','SINGLE_CHOICE','HISTORY','ACTIVE',TRUE,1,@admin),
    ('ELIGIBILITY','What best describes your home?','SINGLE_CHOICE','ENVIRONMENT','ACTIVE',TRUE,2,@admin),
    ('ELIGIBILITY','How many hours a day is someone home with the pet?','SINGLE_CHOICE','LIFESTYLE','ACTIVE',TRUE,3,@admin),
    ('ELIGIBILITY','Do you have other pets or young children?','MULTI_CHOICE','ENVIRONMENT','ACTIVE',TRUE,4,@admin),
    ('ELIGIBILITY','Can you commit to this pet''s care, cost and exercise needs long-term?','YES_NO','COMMITMENT','ACTIVE',TRUE,5,@admin);

SET @e1 := (SELECT id FROM quiz_questions WHERE quiz_type='ELIGIBILITY' AND display_order=1);
SET @e2 := (SELECT id FROM quiz_questions WHERE quiz_type='ELIGIBILITY' AND display_order=2);
SET @e3 := (SELECT id FROM quiz_questions WHERE quiz_type='ELIGIBILITY' AND display_order=3);
SET @e4 := (SELECT id FROM quiz_questions WHERE quiz_type='ELIGIBILITY' AND display_order=4);
SET @e5 := (SELECT id FROM quiz_questions WHERE quiz_type='ELIGIBILITY' AND display_order=5);

INSERT INTO quiz_options (question_id, label, weight, requires_text, display_order) VALUES
    (@e1,'First time',            4, FALSE,1),
    (@e1,'Some experience',      12, FALSE,2),
    (@e1,'Very experienced',     20, FALSE,3),

    (@e2,'House with yard',      20, FALSE,1),
    (@e2,'House, no yard',       14, FALSE,2),
    (@e2,'Apartment',            10, FALSE,3),
    (@e2,'Other',                 5,  TRUE,4),

    (@e3,'Less than 2',           4, FALSE,1),
    (@e3,'2-5',                  10, FALSE,2),
    (@e3,'6-8',                  16, FALSE,3),
    (@e3,'8+',                   20, FALSE,4),

    (@e4,'Dogs',                  5, FALSE,1),
    (@e4,'Cats',                  5, FALSE,2),
    (@e4,'Other pets',            5,  TRUE,3),
    (@e4,'Young kids',            5, FALSE,4),
    (@e4,'None',                 20, FALSE,5),

    (@e5,'Yes',                  20, FALSE,1),
    (@e5,'No',                    0, FALSE,2);


-- -----------------------------------------------------------------------------
-- 5. PERSONALITY QUIZ  (4 questions)
--
-- These questions carry no pass mark. Their job is option_traits below: each
-- answer maps onto the SAME seven traits a pet is tagged with, which is the
-- only bridge between what an adopter says and what a pet is. Without those
-- mappings the match score has nothing to compute on.
-- -----------------------------------------------------------------------------
INSERT INTO quiz_questions
    (quiz_type, question_text, answer_type, category, status, is_required, display_order, created_by)
VALUES
    ('PERSONALITY','How active is your household on an average day?','SINGLE_CHOICE','LIFESTYLE','ACTIVE',TRUE,1,@admin),
    ('PERSONALITY','How much time can you spend directly with the pet?','SINGLE_CHOICE','LIFESTYLE','ACTIVE',TRUE,2,@admin),
    ('PERSONALITY','How busy and noisy is your home?','SINGLE_CHOICE','ENVIRONMENT','ACTIVE',TRUE,3,@admin),
    ('PERSONALITY','What are you hoping for in a companion?','SINGLE_CHOICE','LIFESTYLE','ACTIVE',TRUE,4,@admin);

SET @p1 := (SELECT id FROM quiz_questions WHERE quiz_type='PERSONALITY' AND display_order=1);
SET @p2 := (SELECT id FROM quiz_questions WHERE quiz_type='PERSONALITY' AND display_order=2);
SET @p3 := (SELECT id FROM quiz_questions WHERE quiz_type='PERSONALITY' AND display_order=3);
SET @p4 := (SELECT id FROM quiz_questions WHERE quiz_type='PERSONALITY' AND display_order=4);

INSERT INTO quiz_options (question_id, label, weight, requires_text, display_order) VALUES
    (@p1,'Very active - we are out and moving most days', 0,FALSE,1),
    (@p1,'Moderately active',                            0,FALSE,2),
    (@p1,'Quiet and relaxed',                            0,FALSE,3),

    (@p2,'Most of the day',                              0,FALSE,1),
    (@p2,'Mornings and evenings',                        0,FALSE,2),
    (@p2,'Short periods - the pet must cope alone',      0,FALSE,3),

    (@p3,'Busy - visitors and children often',           0,FALSE,1),
    (@p3,'Somewhere in between',                         0,FALSE,2),
    (@p3,'Very quiet',                                   0,FALSE,3),

    (@p4,'A companion that seeks attention',             0,FALSE,1),
    (@p4,'A playmate',                                   0,FALSE,2),
    (@p4,'A gentle presence that does its own thing',    0,FALSE,3);

-- The bridge: answer -> implied traits. An adopter accumulates the traits of
-- every option they choose; v_adopter_traits exposes the set.
INSERT INTO option_traits (option_id, trait_id)
SELECT o.id, t.id
  FROM quiz_options o
  JOIN quiz_questions q ON q.id = o.question_id
  JOIN (
        SELECT 1 AS qd, 1 AS od, 'energetic'    AS slug UNION ALL
        SELECT 1, 1, 'playful'      UNION ALL
        SELECT 1, 2, 'friendly'     UNION ALL
        SELECT 1, 3, 'calm'         UNION ALL

        SELECT 2, 1, 'affectionate' UNION ALL
        SELECT 2, 2, 'friendly'     UNION ALL
        SELECT 2, 3, 'independent'  UNION ALL

        SELECT 3, 1, 'friendly'     UNION ALL
        SELECT 3, 1, 'playful'      UNION ALL
        SELECT 3, 2, 'calm'         UNION ALL
        SELECT 3, 3, 'shy'          UNION ALL
        SELECT 3, 3, 'calm'         UNION ALL

        SELECT 4, 1, 'affectionate' UNION ALL
        SELECT 4, 2, 'playful'      UNION ALL
        SELECT 4, 2, 'energetic'    UNION ALL
        SELECT 4, 3, 'independent'  UNION ALL
        SELECT 4, 3, 'calm'
       ) m ON m.qd = q.display_order AND m.od = o.display_order
  JOIN traits t ON t.slug = m.slug
 WHERE q.quiz_type = 'PERSONALITY';


-- -----------------------------------------------------------------------------
-- 6. BOARDING QUIZ  (5 questions)
--
-- Asked when an owner requests a stay. Three of these open a free-text
-- follow-up via requires_text -- the feeding schedule, the medication detail
-- and the emergency contact -- which is why that flag exists instead of five
-- hard-coded columns on boarding_requests.
-- -----------------------------------------------------------------------------
INSERT INTO quiz_questions
    (quiz_type, question_text, answer_type, category, status, is_required, display_order, created_by)
VALUES
    ('BOARDING','Is your pet currently healthy and up to date on vaccination?','YES_NO','HEALTH','ACTIVE',TRUE,1,@admin),
    ('BOARDING','How does your pet behave around other animals?','SINGLE_CHOICE','ENVIRONMENT','ACTIVE',TRUE,2,@admin),
    ('BOARDING','What is your feeding schedule?','SINGLE_CHOICE','LIFESTYLE','ACTIVE',TRUE,3,@admin),
    ('BOARDING','Does your pet need any medication during boarding?','YES_NO','HEALTH','ACTIVE',TRUE,4,@admin),
    ('BOARDING','Do you authorise emergency vet care if needed?','YES_NO','HEALTH','ACTIVE',TRUE,5,@admin);

SET @b1 := (SELECT id FROM quiz_questions WHERE quiz_type='BOARDING' AND display_order=1);
SET @b2 := (SELECT id FROM quiz_questions WHERE quiz_type='BOARDING' AND display_order=2);
SET @b3 := (SELECT id FROM quiz_questions WHERE quiz_type='BOARDING' AND display_order=3);
SET @b4 := (SELECT id FROM quiz_questions WHERE quiz_type='BOARDING' AND display_order=4);
SET @b5 := (SELECT id FROM quiz_questions WHERE quiz_type='BOARDING' AND display_order=5);

INSERT INTO quiz_options (question_id, label, weight, requires_text, display_order) VALUES
    (@b1,'Yes',                  0,FALSE,1),
    (@b1,'No',                   0,FALSE,2),

    (@b2,'Sociable with most animals', 0,FALSE,1),
    (@b2,'Cautious but tolerant',      0,FALSE,2),
    (@b2,'Prefers to be alone',        0,FALSE,3),

    (@b3,'Once daily',           0,FALSE,1),
    (@b3,'Twice daily',          0,FALSE,2),
    (@b3,'Free-feed',            0,FALSE,3),
    (@b3,'Custom',               0, TRUE,4),   -- opens "What is the schedule?"

    (@b4,'Yes',                  0, TRUE,1),   -- opens "Medical details"
    (@b4,'No',                   0,FALSE,2),

    (@b5,'Yes',                  0, TRUE,1),   -- opens "Emergency contact number"
    (@b5,'No',                   0,FALSE,2);
