-- =============================================================================
-- V19__views_in_use.sql
--
-- FOUR VIEWS, ALL OF THEM READ BY SOMETHING. That is the whole point of this
-- migration.
--
-- THE PROBLEM IT FIXES. V1 created v_pet_listing with a header saying it is
-- "consumed by FOUR pages", V9 redefined it, and an audit found that NOTHING
-- HAS EVER QUERIED IT. Every one of the eight references to it in the codebase
-- is a comment. Meanwhile the projection it defines was hand-written three
-- separate times in PetService -- which is precisely the drift a view exists to
-- prevent, and it had already happened: the browse query and the view compute
-- the vaccinated flag with two copies of the same expression.
--
-- A view nothing selects from is not a view. It is a comment with syntax.
--
-- WHY IT WENT UNUSED, because the reason matters more than the fix: the view
-- was written to carry the DERIVED columns (cover photo, trait labels, the
-- vaccinated flag, the open-application count) and left out the plain ones the
-- same screens need -- weight, description, neutered, microchipped, the
-- shelter's state. So every caller had to join `pets` back anyway, at which
-- point hand-writing the derived parts too was the shorter road. This migration
-- widens the view until reading it alone is genuinely easier than not.
--
-- WHAT THE FOUR ARE, AND WHO READS EACH:
--
--   v_adopter_traits   already live. PetService.matches / .myTraits,
--                      QuizTakingService.traitsFor.        (3 call sites)
--   v_pet_listing      widened here. PetService.browse / .publicPet, the
--                      public catalogue and the guest pet page.
--   v_shelter_revenue  NEW. AnalyticsService (boarding nights and daycare
--                      subscriptions) and the admin Billing screen.
--   v_daycare_day      NEW. DaycareService.attendance, the staff attendance
--                      history board.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. v_pet_listing -- widened until it can be read on its own.
--
-- CREATE OR REPLACE, as V9 already did to this same view. A view has no data,
-- so replacing one is free: there is nothing to migrate and no downtime beyond
-- the statement itself.
--
-- THE NEW COLUMNS ARE ALL PLAIN pets COLUMNS. Nothing derived was added, and
-- nothing derived was removed -- the four expressions below (cover photo, trait
-- labels, open applications, vaccinated) are exactly as V9 left them, because
-- they are the definitions the rest of the app is supposed to agree with.
--
-- photo_paths IS NEW AND IS NOT THE SAME AS cover_photo_path. The guest pet
-- page shows a gallery; it was building it from its own GROUP_CONCAT. Cover
-- first, then by sort order, so the first element is always the one the
-- cover_photo_path column names.
-- -----------------------------------------------------------------------------
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


-- -----------------------------------------------------------------------------
-- 2. v_shelter_revenue -- what each shelter has billed, by month and by kind.
--
-- WHY A VIEW RATHER THAN A QUERY IN AnalyticsService. Three screens want this
-- arithmetic -- the admin Billing table, the admin analytics charts, and the
-- per-shelter drawer -- and the CASE expressions below are exactly the sort of
-- thing that gets copied with one branch subtly different. billing_slips
-- distinguishes the two kinds by WHICH FOREIGN KEY IS NULL, which is correct
-- (see V13) and easy to get backwards.
--
-- INNER JOIN, so a shelter that has never billed anything is absent rather than
-- present with zeros. That is the honest shape for a revenue report: "no rows
-- for March" and "0.00 for March" are different statements, and the caller can
-- render whichever it wants.
--
-- units MEANS TWO DIFFERENT THINGS and is split into two columns here for that
-- reason -- nights for a boarding slip, months for a subscription. Summing them
-- together would add nights to months, which is how a report ends up with a
-- number nobody can name.
-- -----------------------------------------------------------------------------
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


-- -----------------------------------------------------------------------------
-- 3. v_daycare_day -- one attendance row, with every name already attached.
--
-- SIX JOINS, WRITTEN ONCE. An attendance row on its own says almost nothing: it
-- has a subscription id, a suite id and two staff ids. Turning it into a
-- sentence a person can read -- "Mittens, owned by Sarah, in Cat Loft A, the
-- morning band, 3h20m" -- takes the subscription, the pet, the owner, the
-- shelter, the room and both staff members.
--
-- DaycareService had this join written out inline for its board. Moving it here
-- means the history screen and the live board cannot disagree about what a
-- visit is.
--
-- minutes_on_site COALESCES TO NOW() for a pet that has not gone home, so the
-- number keeps climbing while the animal is there. Not stored, for the reason
-- nights_so_far is not stored: a duration frozen into a column is wrong one
-- minute later.
--
-- LEFT JOIN ON THE CHECK-OUT STAFF MEMBER ONLY. A visit always has somebody who
-- checked the pet in; it has somebody who checked it out only once that has
-- happened. Inner-joining that would silently hide every pet currently on site,
-- which is the exact opposite of what the board is for.
-- -----------------------------------------------------------------------------
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


-- =============================================================================
-- NOTES FOR ANYONE CHANGING THESE
--
-- A VIEW IS NOT A CACHE. None of these store anything; each is re-evaluated on
-- every SELECT. They exist so that a DEFINITION lives in one place -- what
-- "vaccinated" means, what a boarding slip is as opposed to a daycare one, what
-- a visit looks like with its names attached -- not to make anything faster.
--
-- THE INDEXES THAT MAKE THEM CHEAP ARE ON THE TABLES UNDERNEATH. v_daycare_day
-- filters on visit_date, which idx_attendance_board leads with;
-- v_shelter_revenue groups by shelter and issued_at, which idx_slips_shelter
-- covers. A view cannot carry an index of its own, so adding one to a view's
-- WHERE clause means checking the base table has it.
--
-- NONE OF THEM ARE UPDATABLE, and two could not be even in principle:
-- v_shelter_revenue has a GROUP BY and v_daycare_day joins eight tables. Every
-- write in this application goes to a base table through a repository; nothing
-- writes through a view, and nothing should start.
-- =============================================================================
