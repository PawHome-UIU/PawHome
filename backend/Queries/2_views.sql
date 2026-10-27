-- ===========================================================================
-- PawHome — THE VIEWS
-- ===========================================================================
--
-- All four views, with the definition each one owns and who reads it.
-- 
-- WHY WE USE VIEWS AT ALL. A view here is not a cache and not a speed trick
-- — nothing is stored, and each is re-evaluated on every SELECT. A view
-- exists so that a DEFINITION lives in exactly one place. Every one of the
-- four below was created because the same expression had been written out
-- more than once and had started to drift.
-- 
-- All four are read by application code; none is decoration.
-- 
-- Defined in migration V19__views_in_use.sql, except v_adopter_traits which
-- has been live since V1__init.sql. A view has no data, so redefining one
-- with CREATE OR REPLACE costs nothing.


-- ###########################################################################
-- #  v_adopter_traits
-- ###########################################################################

-- WHY IT EXISTS
--   A quiz attempt becomes a weighted trait profile. Three call sites need
--   that profile, and one scoring rule in one place is what stops them
--   disagreeing about what an answer is worth.
--
-- READ BY
--   PetService.matches / .myTraits — pets recommended from a quiz result
--   QuizTakingService.myTraits   — GET /api/v1/traits/mine

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


-- ###########################################################################
-- #  v_pet_listing
-- ###########################################################################

-- WHY IT EXISTS
--   THE DEFINITION OF "VACCINATED" IS THE POINT. At least one COMPLETED shot
--   and nothing SCHEDULED past its due date. That expression had been hand-
--   written into three separate queries and they had already begun to drift
--   apart. The view also owns the cover photo, the gallery, the trait labels
--   and the open-application count, so every screen showing a pet shows the
--   same thing.
--
-- READ BY
--   PetService.SELECT_PUBLIC — the public catalogue and the guest pet page
--       GET /api/v1/pets        GET /api/v1/pets/{id}
--
-- WORTH KNOWING
--   photo_paths and trait_label_list use || as a separator while
--   trait_labels uses ", ". Two columns rather than one because one reader
--   is a human and the other is a parser, and making either re-split the
--   other's separator is how a trait containing a comma becomes two traits.

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

    -- Added at V19 so a reader does not have to join pets back for them.
    p.weight_kg,
    p.description,
    p.is_neutered,
    p.is_microchipped,

    s.id            AS shelter_id,
    s.name          AS shelter_name,
    s.city          AS shelter_city,
    s.state         AS shelter_state,      -- V19

    (SELECT ph.file_path
       FROM pet_photos ph
      WHERE ph.pet_id = p.id AND ph.is_cover = TRUE
      LIMIT 1)                                        AS cover_photo_path,

    -- V19. The gallery, cover first. '||' rather than ', ' because a file path
    -- may not contain it and a comma-separated list of paths is ambiguous the
    -- day somebody uploads a file with a comma in the name.
    (SELECT GROUP_CONCAT(ph.file_path
                         ORDER BY ph.is_cover DESC, ph.sort_order SEPARATOR '||')
       FROM pet_photos ph
      WHERE ph.pet_id = p.id)                         AS photo_paths,

    -- UNCHANGED FROM V9, and deliberately still ', ' -- this one is read by a
    -- human, not split by a parser.
    (SELECT GROUP_CONCAT(t.label ORDER BY t.label SEPARATOR ', ')
       FROM pet_traits pt
       JOIN traits t ON t.id = pt.trait_id
      WHERE pt.pet_id = p.id)                         AS trait_labels,

    -- V19. The same list again, '||'-separated, for the code that splits it.
    -- Two columns rather than one because the two readers want two things and
    -- making either re-split the other's separator is how a trait containing a
    -- comma becomes two traits.
    (SELECT GROUP_CONCAT(t.label ORDER BY t.label SEPARATOR '||')
       FROM pet_traits pt
       JOIN traits t ON t.id = pt.trait_id
      WHERE pt.pet_id = p.id)                         AS trait_label_list,

    (SELECT COUNT(*)
       FROM adoption_applications a
      WHERE a.pet_id = p.id AND a.status = 'PENDING') AS open_application_count,

    -- THE DEFINITION OF "VACCINATED", AND THE REASON THIS VIEW MATTERS MOST.
    -- At least one COMPLETED shot and nothing SCHEDULED past its due date. The
    -- browse query had its own copy of this expression; HealthDtos comments
    -- that its `vaccinated` flag is "THE SAME EXPRESSION v_pet_listing uses",
    -- which is a promise no compiler was checking.
    (    EXISTS (SELECT 1 FROM vaccinations v
                  WHERE v.pet_id = p.id AND v.status = 'COMPLETED')
     AND NOT EXISTS (SELECT 1 FROM vaccinations v2
                      WHERE v2.pet_id = p.id
                        AND v2.status = 'SCHEDULED'
                        AND v2.next_due_on < CURDATE())
    )                                                 AS is_vaccinated

FROM pets p
JOIN shelters s ON s.id = p.shelter_id
-- A household pet is not a listing. Redundant against the join above, and kept
-- so that it stays true if anyone ever loosens it.
WHERE p.shelter_id IS NOT NULL;


-- ###########################################################################
-- #  v_shelter_revenue
-- ###########################################################################

-- WHY IT EXISTS
--   billing_slips.units MEANS TWO DIFFERENT THINGS — nights on a boarding
--   slip, months on a subscription. The view splits them into separate
--   columns so that no caller can sum them together and produce a number
--   that is nights added to months. Three screens read this arithmetic.
--
-- READ BY
--   AnalyticsService.revenueTile / .nightsTile / .revenueByMonth /
--   .revenueByShelter — all four tiles and charts on GET /api/v1/admin/analytics

CREATE OR REPLACE VIEW v_shelter_revenue AS
SELECT
    s.id                                          AS shelter_id,
    s.name                                        AS shelter_name,
    DATE_FORMAT(bs.issued_at, '%Y-%m-01')         AS billed_month,

    SUM(bs.boarding_request_id IS NOT NULL)       AS boarding_slips,
    COALESCE(SUM(CASE WHEN bs.boarding_request_id IS NOT NULL
                      THEN bs.units END), 0)      AS nights_billed,
    COALESCE(SUM(CASE WHEN bs.boarding_request_id IS NOT NULL
                      THEN bs.total END), 0.00)   AS boarding_total,

    SUM(bs.subscription_id IS NOT NULL)           AS daycare_slips,
    COALESCE(SUM(CASE WHEN bs.subscription_id IS NOT NULL
                      THEN bs.units END), 0)      AS months_billed,
    COALESCE(SUM(CASE WHEN bs.subscription_id IS NOT NULL
                      THEN bs.total END), 0.00)   AS daycare_total,

    COUNT(*)                                      AS slips,
    SUM(bs.total)                                 AS total_billed

FROM       billing_slips bs
JOIN       shelters      s ON s.id = bs.shelter_id
GROUP BY   s.id, s.name, DATE_FORMAT(bs.issued_at, '%Y-%m-01');


-- ###########################################################################
-- #  v_daycare_day
-- ###########################################################################

-- WHY IT EXISTS
--   An attendance row on its own says almost nothing: a subscription id, a
--   suite id and two staff ids. Eight tables turn it into a sentence a
--   person can read. Written once here so the live board and the history
--   screen cannot disagree about what a visit is.
--
-- READ BY
--   DaycareService.SELECT_VISIT — the staff attendance board and history
--       GET /api/v1/staff/daycare/attendance
--
-- WORTH KNOWING
--   minutes_on_site COALESCEs to NOW(), so the number keeps climbing while
--   the animal is on site. A duration frozen into a column is wrong one
--   minute later. The check-OUT staff member is a LEFT JOIN: every visit has
--   somebody who checked the pet in, but only a finished visit has somebody
--   who checked it out — an inner join there would hide every pet currently
--   in the building.

CREATE OR REPLACE VIEW v_daycare_day AS
SELECT
    a.id                AS attendance_id,
    a.visit_date,
    a.slot,

    ds.id               AS subscription_id,
    ds.shelter_id,
    sh.name             AS shelter_name,

    p.id                AS pet_id,
    p.name              AS pet_name,
    p.species,

    o.id                AS owner_id,
    o.full_name         AS owner_name,
    o.email             AS owner_email,

    su.id               AS suite_id,
    su.name             AS suite_name,

    a.checked_in_at,
    a.checked_out_at,
    (a.checked_out_at IS NULL)                     AS still_here,
    TIMESTAMPDIFF(MINUTE, a.checked_in_at,
                  COALESCE(a.checked_out_at, NOW())) AS minutes_on_site,

    si.full_name        AS checked_in_by_name,
    so.full_name        AS checked_out_by_name

FROM       daycare_attendance    a
JOIN       daycare_subscriptions ds ON ds.id = a.subscription_id
JOIN       pets                  p  ON p.id  = ds.pet_id
JOIN       users                 o  ON o.id  = ds.owner_id
JOIN       shelters              sh ON sh.id = ds.shelter_id
JOIN       shelter_suites        su ON su.id = a.suite_id
JOIN       users                 si ON si.id = a.checked_in_by
LEFT JOIN  users                 so ON so.id = a.checked_out_by;


-- ===========================================================================
-- A NOTE ON COST, BECAUSE IT CUTS AGAINST US
-- ===========================================================================
--
-- Reading the public catalogue through v_pet_listing is measurably slower
-- than the same projection written out by hand — MySQL materialises the view
-- before applying the outer ORDER BY and LIMIT, so the correlated subqueries
-- in its SELECT list run for every matching row rather than for the twelve
-- rows actually returned. LIMIT cannot be pushed through a materialisation.
-- We kept the view anyway: it buys one definition of "vaccinated" across
-- five screens, which is what we wanted. The trade is recorded here so that
-- whoever revisits the catalogue page knows exactly what it is.
