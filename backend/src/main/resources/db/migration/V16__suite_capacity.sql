-- =============================================================================
-- V16__suite_capacity.sql
--
-- A ROOM HOLDS MORE THAN ONE ANIMAL, AND UNTIL NOW THE SCHEMA SAID OTHERWISE.
--
-- shelter_suites has been (id, shelter_id, name, species, is_active) since V1,
-- with no idea how big a room is. Every rule built on top of it therefore had
-- to assume the smallest possible room:
--
--   * BoardingService.accept refused a suite if ANY overlapping stay held it;
--   * DaycareService.checkIn refused a suite if ANY pet held the same band,
--     which uq_suite_band enforced in the database as well.
--
-- That is right for a kennel and wrong for a cat loft. "Cat Loft A" is one
-- room with twenty places in it, and a shelter that has to create twenty rows
-- called "Cat Loft A 1".."Cat Loft A 20" to express that is being made to work
-- around its own record system.
--
-- SO capacity BECOMES A COLUMN AND THE TWO "ANY" RULES BECOME COUNTS.
--
-- WHY NOT DERIVE IT. There is nothing to derive it from. A room's size is a
-- fact about the building -- how many runs are bolted to the floor -- and the
-- only place it exists is in the head of the person who works there. So it is
-- stored, it is theirs to edit (ShelterService.createSuite / updateSuite), and
-- the seed below is a starting point rather than an answer.
--
-- WHY NOT ON shelters. shelters.capacity already exists and means something
-- else: the whole building's licensed headcount, the denominator of the
-- "28 / 50" bar. This is the denominator of ONE ROOM's bar. They are related
-- only in that the sum of the second should not exceed the first -- which is
-- deliberately NOT enforced here, because a shelter mid-renovation with the
-- rooms entered and the licence not yet raised would be locked out of its own
-- record by a constraint that is really a policy.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. The column.
--
-- DEFAULT 1 IS THE CONSERVATIVE CHOICE AND IT IS DELIBERATE. 1 reproduces
-- exactly the behaviour every row had before this migration -- one animal per
-- room -- so a suite that nobody ever gets round to sizing keeps refusing a
-- second occupant rather than silently accepting an unbounded number. A
-- default that guessed high would turn "we never configured this" into "this
-- room takes twelve dogs", which is a claim about a building nobody made.
-- -----------------------------------------------------------------------------
ALTER TABLE shelter_suites
    ADD COLUMN capacity INT NOT NULL DEFAULT 1
        COMMENT 'How many animals fit in this room at once. NOT shelters.capacity.';

-- > 0, for the same reason ck_shelters_capacity says it: a room with no places
-- in it is a retired room, and is_active is how that is said.
--
-- The upper bound is a typo catcher, not a policy -- the same job the
-- @Max(100000) on CreateShelterRequest.capacity does one layer up.
ALTER TABLE shelter_suites
    ADD CONSTRAINT ck_suites_capacity CHECK (capacity > 0 AND capacity <= 500);


-- -----------------------------------------------------------------------------
-- 2. Seed the rooms V8 created.
--
-- V8 built six rooms for every shelter: three dog kennels, two cat lofts and a
-- quiet room. Those are the only suites in existence, they were built by a
-- migration rather than by a shelter, and leaving all six at 1 would mean the
-- feature ships invisible -- the same mistake V13 made with
-- daycare_monthly_price and V14 had to come back and fix.
--
-- IDEMPOTENT BY THE SAME TRICK AS V14: the WHERE clause tests for the default,
-- so a shelter that has since set Cat Loft A to 12 keeps 12 if this ever runs
-- against a database that already had it.
--
-- The numbers are what the words mean. A kennel is a run for one dog, with
-- room for a bonded pair. A loft is a communal cat room. A quiet room is for
-- the animals that cannot be in either.
-- -----------------------------------------------------------------------------
UPDATE shelter_suites SET capacity = 20
 WHERE capacity = 1 AND species = 'CAT'  AND name LIKE 'Cat Loft%';

UPDATE shelter_suites SET capacity =  2
 WHERE capacity = 1 AND species = 'DOG'  AND name LIKE 'Kennel%';

UPDATE shelter_suites SET capacity =  4
 WHERE capacity = 1 AND species IS NULL  AND name = 'Quiet Room';


-- -----------------------------------------------------------------------------
-- 3. uq_suite_band has to go, and the order of these two statements matters.
--
-- V15 wrote:
--     UNIQUE KEY uq_suite_band (suite_id, visit_date, slot)
-- meaning "one animal per room per band". That is precisely the rule this
-- migration is replacing with a count, so the constraint is now wrong: with
-- Cat Loft A sized at twenty, the second cat of the morning would be refused
-- by the database no matter what the service decided.
--
-- IT CANNOT SIMPLY BE DROPPED. fk_attendance_suite is a foreign key on
-- suite_id, and InnoDB requires an index whose LEADING column is suite_id to
-- support it. uq_suite_band was that index, so MySQL will refuse the DROP with
-- errno 150 while it is the only candidate. The replacement therefore goes in
-- FIRST, leading on suite_id, and the DROP succeeds because the FK has
-- somewhere else to live.
--
-- THE REPLACEMENT IS NOT A COPY. It carries checked_out_at as a fourth column,
-- because the query that replaces the constraint --
-- DaycareAttendanceRepository.countPresentInSuiteBand -- reads exactly
-- (suite_id, visit_date, slot) and then filters on checked_out_at IS NULL. All
-- four in the index means that count is answered from the index alone.
-- -----------------------------------------------------------------------------
ALTER TABLE daycare_attendance
    ADD KEY idx_attendance_suite_band (suite_id, visit_date, slot, checked_out_at);

ALTER TABLE daycare_attendance
    DROP INDEX uq_suite_band;

-- uq_visit_per_day (subscription_id, visit_date) IS NOT TOUCHED. "One visit
-- per pet per day" is still true and still a fact about one subscription, not
-- about a room -- it is what makes checkIn idempotent, and nothing above it
-- has changed.


-- =============================================================================
-- WHAT IS NOW THE TRANSACTION'S JOB RATHER THAN THE SCHEMA'S
--
-- Dropping uq_suite_band moves a guarantee out of the database, so it is worth
-- being explicit about where it went and why it could not stay.
--
-- The rule is no longer "no second row with this (suite, date, band)" -- which
-- a unique key expresses perfectly -- but "no more rows with this (suite,
-- date, band) than shelter_suites.capacity says". That is a COUNT of rows in
-- one table compared against a column in ANOTHER, evaluated at write time.
-- MySQL cannot express it:
--
--   * a CHECK constraint may not contain a subquery, so it cannot count;
--   * a UNIQUE key counts to exactly one and no further;
--   * and there is no EXCLUDE constraint, which is the thing that would.
--
-- So it lands where the boarding overlap rule already lives: a short
-- transaction that takes SELECT ... FOR UPDATE over the rows it is about to
-- count, counts them, decides, writes, and commits. Two staff members checking
-- two cats into the last place in Cat Loft A both read "nineteen" without the
-- lock, and the twenty-first cat is discovered when it is carried in.
--
-- The same shape now applies one level up, in BoardingService.accept:
-- shelters.capacity is the building's licensed headcount, and a shelter
-- already at it must not take another boarder for those dates. Same reason,
-- same impossibility, same answer.
-- =============================================================================
