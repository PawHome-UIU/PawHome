-- =============================================================================
-- V3__seed_shelters.sql
--
-- WHY THIS EXISTS
-- ---------------
-- V2 seeds traits, vaccine types, the bootstrap admin and the three quizzes --
-- but no shelters. That left a hole nobody noticed until someone tried to sign
-- up as staff:
--
--   users.shelter_id -> fk_users_shelter -> shelters.id
--   ck_users_staff_shelter: a STAFF row MUST have a shelter_id
--
-- With an empty shelters table there is no id a staff sign-up could name, so
-- registration could not succeed at all. The account was never created, and the
-- next login attempt then said "that email and password do not match" -- which
-- was true, and completely misleading.
--
-- Five shelters, matching the names the frontend's placeholder data already
-- uses, so the dev UI and the database agree until GET /api/v1/shelters exists.
--
-- INSERT IGNORE and explicit ids, for the same reason as V2: re-running this
-- against a database that already has them is a no-op rather than a duplicate
-- key error, and the ids are fixed so anything referencing shelter 1 keeps
-- meaning the same shelter.
-- =============================================================================

INSERT IGNORE INTO shelters
    (id, name, email, phone, address_line, city, state, zip,
     capacity, status, description, boarding_rate_daily)
VALUES
    (1, 'Evergreen Paws',
     'hello@evergreenpaws.org', '(555) 010-2201',
     '18 Cedar Row', 'Portland', 'OR', '97205',
     100, 'ACTIVE',
     'A long-running rescue focused on dogs recovering from neglect.', 32.00),

    (2, 'Tail Waggers Haven',
     'contact@tailwaggers.org', '(555) 010-4417',
     '204 Willow Street', 'Austin', 'TX', '78701',
     180, 'ACTIVE',
     'The largest shelter in the network, taking both cats and dogs.', 28.50),

    (3, 'Urban Kitty Rescue',
     'team@urbankitty.org', '(555) 010-7788',
     '9 Harbour Lane', 'Seattle', 'WA', '98101',
     120, 'ACTIVE',
     'Cats only, with a strong foster network across the city.', 22.00),

    (4, 'South Valley Rescue',
     'admin@southvalley.org', '(555) 010-3390',
     '1140 Mesa Drive', 'Austin', 'TX', '78745',
     50, 'ACTIVE',
     'A small operation specialising in bonded pairs.', 26.00),

    (5, 'Senior Dog Haven',
     'care@seniordoghaven.org', '(555) 010-9012',
     '77 Alder Court', 'New York', 'NY', '10025',
     90, 'ACTIVE',
     'Older dogs, hospice fostering and end-of-life care.', 30.00);


-- -----------------------------------------------------------------------------
-- A demo staff account, APPROVED, so the role matrix can be walked end to end
-- without first registering and then approving somebody.
--
--   staff@pawhome.local  /  admin123        (the SAME hash as the admin -- this
--                                            is a public dev credential, not a
--                                            secret)
--
-- ** CHANGE OR REMOVE BEFORE ANY DEMO THAT MATTERS. ** Like the admin row in
-- V2, this exists so a fresh clone can demonstrate STAFF access immediately.
--
-- shelter_id = 1 is required: ck_users_staff_shelter refuses a STAFF row
-- without one, and refuses a shelter_id on anyone else.
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO users
    (full_name, email, password_hash, role, approval_status, is_active,
     shelter_id, job_title)
VALUES ('Sarah Jenkins',
        'staff@pawhome.local',
        '$2b$10$3FdzBhbcG8yo4hLF9yosuOHu5I2zPKJsWFzqqObxa4eCFM3.YWflG',
        'STAFF', 'APPROVED', TRUE,
        1, 'Shelter Manager');


-- -----------------------------------------------------------------------------
-- A PENDING staff account.
--
-- Not decoration: this is the row that proves the two-flag enabled rule. It has
-- a correct password and still cannot sign in, because approval_status is
-- PENDING -- and it will keep being refused on every request even if a token is
-- somehow obtained, because JwtAuthFilter re-checks on each one.
--
--   pending@pawhome.local  /  admin123   -> must fail with 403, not 401
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO users
    (full_name, email, password_hash, role, approval_status, is_active,
     shelter_id, job_title)
VALUES ('Julianne Davis',
        'pending@pawhome.local',
        '$2b$10$3FdzBhbcG8yo4hLF9yosuOHu5I2zPKJsWFzqqObxa4eCFM3.YWflG',
        'STAFF', 'PENDING', TRUE,
        2, 'Lead Volunteer');


-- -----------------------------------------------------------------------------
-- A demo adopter, for the third row of the matrix.
--
--   adopter@pawhome.local  /  admin123
--
-- shelter_id stays NULL -- ck_users_staff_shelter forbids one on a non-STAFF
-- row, which is the constraint working in the other direction.
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO users
    (full_name, email, password_hash, role, approval_status, is_active)
VALUES ('Sarah Johnson',
        'adopter@pawhome.local',
        '$2b$10$3FdzBhbcG8yo4hLF9yosuOHu5I2zPKJsWFzqqObxa4eCFM3.YWflG',
        'ADOPTER', 'APPROVED', TRUE);
