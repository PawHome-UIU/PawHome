-- =============================================================================
-- V8__seed_shelter_suites.sql
--
-- WHY THIS EXISTS
-- ---------------
-- shelter_suites was created in V1 and then never filled by anything. That is
-- fine until boarding is wired, at which point it stops being cosmetic:
--
--   boarding_requests.suite_id -> fk_boarding_suite -> shelter_suites.id
--
-- Staff assign a suite when they accept a request. With the table empty the
-- accept dialog offers an empty dropdown, and the only way to accept anything
-- is to leave suite_id NULL -- which the column permits, so nothing would ever
-- complain, and the shelter would simply have no idea where the animal is.
-- Exactly the V3 problem again: a hole a foreign key cannot point out because
-- there is nothing on the other side to point at.
--
-- SIX PER SHELTER, DERIVED FROM shelters RATHER THAN HARD-CODED
--
-- V3 seeds five shelters with fixed ids and this could name them the same way.
-- It deliberately does not: an admin who adds a sixth shelter through the UI
-- should get suites for it too, and a hard-coded list silently would not cover
-- them. The CROSS JOIN gives every shelter that exists when this runs the same
-- six rooms, and the service can hand later shelters their own.
--
-- SPECIES IS THE POINT OF THE SPECIES COLUMN. Three dog kennels, two cat lofts
-- and one room that takes anything (NULL = no restriction, per V1). A boarding
-- request for a cat should not be offered a dog run, and having every suite
-- accept every species would make the column decorative.
--
-- INSERT IGNORE, as in V2 and V3. uq_suite_per_shelter is UNIQUE on
-- (shelter_id, name), so re-running this against a database that already has
-- these rows is a no-op rather than a duplicate-key failure -- and, unlike V3,
-- this file does not need fixed ids to get that property, because the unique
-- key is on the natural pair rather than on the surrogate.
-- =============================================================================

INSERT IGNORE INTO shelter_suites (shelter_id, name, species, is_active)
SELECT  s.id, v.name, v.species, TRUE
FROM    shelters s
CROSS JOIN (
    -- CAST on the first branch only: a UNION takes its column type from the
    -- widest branch, and leaving that to be inferred from a 3-character
    -- literal is the kind of thing that breaks when someone adds 'RABBIT'.
              SELECT 'Kennel 1'   AS name, CAST('DOG' AS CHAR(10)) AS species
    UNION ALL SELECT 'Kennel 2',        'DOG'
    UNION ALL SELECT 'Kennel 3',        'DOG'
    UNION ALL SELECT 'Cat Loft A',      'CAT'
    UNION ALL SELECT 'Cat Loft B',      'CAT'
    -- NULL, not 'OTHER'. V1's comment is explicit: NULL means the suite takes
    -- any species, whereas 'OTHER' would mean it takes only animals that are
    -- neither dog nor cat -- the opposite of a general-purpose room.
    UNION ALL SELECT 'Quiet Room',      NULL
) v;
