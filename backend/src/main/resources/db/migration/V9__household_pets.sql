-- =============================================================================
-- V9__household_pets.sql
--
-- THE HOLE THIS FILLS
-- -------------------
-- Boarding is for an animal somebody owns. Until now the only way a pets row
-- could gain an owner_id was an approved adoption, so the boarding module could
-- only ever serve pets this network had rehomed -- and a person who has had a
-- dog at home for nine years could not book a single night. That is not a
-- missing screen; it is the data model refusing to describe the customer.
--
-- Two columns stood in the way, and both are fixed here.
--
-- 1. shelter_id WAS NOT NULL
--
-- V1 calls it the "shelter of record ... reporting must keep crediting the
-- shelter that did the work", which is exactly right for an animal the network
-- took in and exactly meaningless for one that has never been in a shelter at
-- all. Rather than inventing a credit -- pointing a household pet at whichever
-- shelter it happens to board with first, which would put it in that shelter's
-- Manage Pets list as an animal they never took in, inflate their pet count,
-- and go stale the moment the owner boards somewhere else -- the column becomes
-- nullable and NULL is given a meaning:
--
--     shelter_id IS NULL  ->  this animal belongs to a household, not to the
--                             network. No shelter did any work; none should be
--                             credited, listed against it, or counted.
--
-- v_pet_listing already drops these rows, because it INNER JOINs shelters. That
-- is the correct behaviour arrived at by accident, so the view is recreated
-- below with the condition stated outright: a future reader who "fixes" the
-- inner join to a LEFT JOIN would otherwise put private pets on the public
-- browse page, and nothing would have warned them.
--
-- 2. THERE WAS NO STATUS THAT MEANT "SOMEBODY'S PET"
--
-- ck_pets_owner_when_placed forces any pet with an owner to be ADOPTED or
-- BOARDING. A household pet is neither: it was never adopted from anyone, and
-- it is only BOARDING during a stay. Reusing ADOPTED would have been one line
-- of migration and a lie on every screen -- a pet raised from a puppy at home,
-- labelled "Adopted" for the rest of its life -- so the enum gains OWNED and
-- the constraint is widened to admit it.
--
-- The constraint keeps both halves, as V1 insisted: a placed pet has an owner,
-- an unplaced pet has none, and neither half can drift because both are the
-- same CHECK.
--
-- 3. THE NEW RULE THE NULLABLE COLUMN NEEDS
--
-- ck_pets_household closes the gap the first change opens. Without it a row
-- could have no shelter AND be AVAILABLE -- an animal on the adoption
-- catalogue belonging to nobody and offered by no one. Because an OWNED or
-- BOARDING pet must already have an owner by the check above, this single
-- constraint gives the whole chain:
--
--     shelter_id IS NULL  ->  status IN ('OWNED','BOARDING')  ->  owner_id IS NOT NULL
--
-- so a shelterless pet is always somebody's, without a second constraint
-- saying so separately.
--
-- NOTHING EXISTING CHANGES MEANING. Every current row has a shelter_id and a
-- status from the old five, so this migration adds possibilities rather than
-- reinterpreting anything already stored.
-- =============================================================================


-- ------------------------------------------------- 1. a pet may have no shelter
ALTER TABLE pets
    MODIFY COLUMN shelter_id BIGINT NULL
        COMMENT 'Shelter of record. NULL = a household pet, owned from home.';


-- ------------------------------------------------------------- 2. OWNED
-- MODIFY restates the whole definition, so the DEFAULT has to be repeated or
-- it is dropped along the way. OWNED is added before UNAVAILABLE rather than
-- appended: nothing orders by this enum's ordinal today, and keeping the
-- placed statuses together reads better in a SHOW CREATE TABLE.
ALTER TABLE pets
    MODIFY COLUMN status
        ENUM('AVAILABLE','PENDING','ADOPTED','BOARDING','OWNED','UNAVAILABLE')
        NOT NULL DEFAULT 'AVAILABLE';


-- ------------------------------------- 3. the two checks, dropped and rewritten
-- A CHECK cannot be altered in place; it is replaced. Dropping first means a
-- failure in the ADD leaves the table with no constraint at all, so the two
-- statements belong together in one migration and Flyway runs them in one
-- transaction where the engine allows it.
ALTER TABLE pets
    DROP CHECK ck_pets_owner_when_placed;

ALTER TABLE pets
    ADD CONSTRAINT ck_pets_owner_when_placed CHECK (
        (status IN     ('ADOPTED','BOARDING','OWNED') AND owner_id IS NOT NULL) OR
        (status NOT IN ('ADOPTED','BOARDING','OWNED') AND owner_id IS     NULL)
    );

ALTER TABLE pets
    ADD CONSTRAINT ck_pets_household CHECK (
        shelter_id IS NOT NULL OR status IN ('OWNED','BOARDING')
    );


-- --------------------------------------- 4. say out loud what the view excludes
-- Identical to V1's definition but for the WHERE clause. The INNER JOIN already
-- excluded shelterless pets; now that such pets exist on purpose, the exclusion
-- is written down instead of being a side effect nobody would think to preserve.
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
JOIN shelters s ON s.id = p.shelter_id
-- A household pet is not a listing. Redundant against the join above, and kept
-- so that it stays true if anyone ever loosens it.
WHERE p.shelter_id IS NOT NULL;
