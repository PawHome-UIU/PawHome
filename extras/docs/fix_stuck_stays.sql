-- =============================================================================
-- THE TWO STUCK STAYS — diagnose, then choose ONE remedy.
--
-- Run with:
--   docker exec -i pawhome-mysql mysql -u pawhome -ppawhome_dev_pw pawhome
--
-- WHAT WENT WRONG. checkIn() refused a stay whose end_date had passed but not
-- one whose start_date had not yet arrived, so a stay booked for the future
-- could be set ACTIVE today. complete() then wrote picked_up_on = today, which
-- is earlier than start_date, and ck_boarding_picked_up refused it — correctly,
-- because "collected before it arrived" is a bad row. The pet could be checked
-- in and then never checked out.
--
-- READ THIS FIRST: YOU PROBABLY DO NOT NEED ANY OF THE REMEDIES BELOW.
-- BoardingService.complete() now floors picked_up_on at start_date, so once the
-- backend is rebuilt these two check out normally through the UI and get their
-- bills. Use STEP 1 to look, then just press Check out. The remedies are for
-- the case where you would rather the two stays had never happened.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- STEP 1 — LOOK. Run this on its own first.
--
-- Any row with days_early > 0 is one of the stuck ones: it was checked in that
-- many days before it was booked to start.
-- -----------------------------------------------------------------------------
SELECT  b.id            AS stay_id,
        p.id            AS pet_id,
        p.name          AS pet,
        p.status        AS pet_status,
        u.full_name     AS owner,
        b.status        AS stay_status,
        b.start_date,
        b.end_date,
        b.picked_up_on,
        DATEDIFF(b.start_date, CURDATE()) AS days_early,
        (SELECT COUNT(*) FROM billing_slips s  WHERE s.boarding_request_id = b.id) AS slips,
        (SELECT COUNT(*) FROM boarding_charges c WHERE c.boarding_id       = b.id) AS charges
FROM        boarding_requests b
JOIN        pets  p ON p.id = b.pet_id
JOIN        users u ON u.id = b.owner_id
WHERE       b.status = 'ACTIVE'
  AND       b.start_date > CURDATE()
ORDER BY    b.id;

-- If `slips` is anything but 0, STOP. That stay has been billed and none of the
-- remedies below apply to it — fk_slip_boarding is ON DELETE RESTRICT and a
-- customer has been handed a document. Ask before touching it.


-- =============================================================================
-- REMEDY A — CANCEL THEM.  *** RECOMMENDED IF YOU DO NOT WANT THE STAYS ***
--
-- Keeps the record (a stay was booked and cancelled, which is what happened)
-- and frees the pet. Nothing is destroyed, nothing is billed.
--
-- THE SECOND UPDATE IS THE ONE PEOPLE FORGET. checkIn() set pets.status to
-- BOARDING; the stay row is not what makes the pet boarded, that column is. Fix
-- only the stay and the pet stays BOARDING forever — it will keep showing as
-- "in care", and every future booking for it will be refused.
--
-- restingStatus(): a pet with a shelter of record goes back to ADOPTED, one
-- registered from a household goes back to OWNED. ck_pets_owner_when_placed
-- refuses AVAILABLE while owner_id is set, so neither may be spelled that way.
-- =============================================================================
-- START TRANSACTION;
--
-- UPDATE boarding_requests
--    SET status = 'CANCELLED', suite_id = NULL
--  WHERE status = 'ACTIVE' AND start_date > CURDATE();
--
-- UPDATE pets p
--    SET p.status = IF(p.shelter_id IS NULL, 'OWNED', 'ADOPTED')
--  WHERE p.status = 'BOARDING'
--    AND NOT EXISTS (SELECT 1 FROM boarding_requests b
--                     WHERE b.pet_id = p.id AND b.status = 'ACTIVE');
--
-- COMMIT;


-- =============================================================================
-- REMEDY B — PULL THE DATES BACK TO TODAY, then check out in the UI.
--
-- Says the stay started today, which for a pet that has actually been in the
-- building since check-in is the truer record. The bill then charges from
-- today. ck_boarding_dates requires end_date > start_date, so end_date is
-- pushed out only if it would otherwise collide.
-- =============================================================================
-- UPDATE boarding_requests
--    SET end_date   = IF(end_date > CURDATE(), end_date, CURDATE() + INTERVAL 1 DAY),
--        start_date = CURDATE()
--  WHERE status = 'ACTIVE' AND start_date > CURDATE();


-- =============================================================================
-- REMEDY C — DELETE THEM.  *** LAST RESORT; READ THE TWO TRAPS ***
--
-- TRAP 1: THE CASCADE IS WIDER THAN IT LOOKS. Deleting a boarding_requests row
-- takes its instructions, updates, care_logs, care_tasks and charges with it
-- (all ON DELETE CASCADE). The owner's whole timeline for that stay is gone,
-- and none of it comes back.
--
-- TRAP 2: THE PET IS NOT FREED BY THE DELETE. pets.status is a column on pets,
-- not a consequence of the stay existing, so the pet stays BOARDING and becomes
-- unbookable. The pet UPDATE must run and must run FIRST, while the rows are
-- still there to identify them by.
--
-- fk_slip_boarding is ON DELETE RESTRICT, so a stay that has been billed will
-- refuse to delete. That is the database protecting a customer's bill, not an
-- obstacle to work around.
-- =============================================================================
-- START TRANSACTION;
--
-- -- Free the pets FIRST, while the stays still identify them.
-- UPDATE pets p
--    JOIN boarding_requests b ON b.pet_id = p.id
--    SET p.status = IF(p.shelter_id IS NULL, 'OWNED', 'ADOPTED')
--  WHERE b.status = 'ACTIVE' AND b.start_date > CURDATE()
--    AND p.status = 'BOARDING';
--
-- DELETE FROM boarding_requests
--  WHERE status = 'ACTIVE' AND start_date > CURDATE();
--
-- COMMIT;


-- -----------------------------------------------------------------------------
-- STEP 3 — CHECK. Every count should be 0.
-- -----------------------------------------------------------------------------
SELECT
  (SELECT COUNT(*) FROM boarding_requests
    WHERE status = 'ACTIVE' AND start_date > CURDATE())      AS stays_still_stuck,
  (SELECT COUNT(*) FROM pets p
    WHERE p.status = 'BOARDING'
      AND NOT EXISTS (SELECT 1 FROM boarding_requests b
                       WHERE b.pet_id = p.id AND b.status = 'ACTIVE'))
                                                             AS pets_stranded_as_boarding,
  (SELECT COUNT(*) FROM boarding_requests
    WHERE picked_up_on IS NOT NULL AND picked_up_on < start_date)
                                                             AS rows_the_check_would_refuse;
