-- =============================================================================
-- DELETE A WORKER (a STAFF account)
--
-- Run with:
--   docker exec -it pawhome-mysql mysql -u pawhome -ppawhome_dev_pw pawhome
--
-- Then paste the steps below ONE AT A TIME. Do not paste the whole file.
--
-- -----------------------------------------------------------------------------
-- READ THIS FIRST: A REAL DELETE IS USUALLY THE WRONG ANSWER, AND THE SCHEMA
-- IS BUILT TO STOP YOU.
--
-- Eighteen foreign keys point at `users` with ON DELETE RESTRICT. A staff
-- member who has ever checked a pet in, posted a boarding update, recorded a
-- vaccination, uploaded a photo or reviewed an application CANNOT be deleted
-- and should not be: the vaccination record would lose the name of the person
-- who gave the shot, and that name is the point of recording it.
--
-- The designed answer is `is_active = FALSE` -- STEP 3. It is not a soft
-- delete or a compromise: `User.isEnabled()` checks it, and `JwtAuthFilter`
-- re-checks it on EVERY REQUEST, so the account stops working the moment you
-- run it -- not when their token expires. Their history stays readable.
--
-- The Admin -> Users page already has this as a button. The SQL is here for
-- when you want it from the terminal.
--
-- A hard DELETE is correct for exactly one case: an account created by mistake
-- that has never done anything. STEP 4 does it, and STEP 2 tells you whether
-- you are in that case.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- STEP 1 -- FIND THE WORKER, and set @wid.
--
-- Change the email on the first line. Match by EMAIL, not name: uq_users_email
-- guarantees email is unique and two people can share a full_name.
-- -----------------------------------------------------------------------------
SET @email = 'worker@example.com';

SELECT  u.id, u.full_name, u.email, u.role, u.job_title,
        u.approval_status, u.is_active,
        s.name AS shelter, u.created_at
FROM        users u
LEFT JOIN   shelters s ON s.id = u.shelter_id
WHERE       u.email = @email;

-- Exactly one row should come back. If zero, the email is wrong -- look with:
--   SELECT id, full_name, email, role, is_active FROM users WHERE role = 'STAFF';

SET @wid = (SELECT id FROM users WHERE email = @email);
SELECT @wid AS worker_id;   -- NULL here means STOP, nothing was found.


-- -----------------------------------------------------------------------------
-- STEP 2 -- WHAT BLOCKS THE DELETE.
--
-- Every ON DELETE RESTRICT reference to this user. Run it before anything else.
--
--   total_blocking = 0  ->  STEP 4 (hard delete) will work.
--   total_blocking > 0  ->  the delete WILL be refused by MySQL. Use STEP 3.
--
-- The rows are not a problem to be cleared. They are this person's work.
-- -----------------------------------------------------------------------------
SELECT 'pets.created_by'                 AS reference, COUNT(*) AS rows_held FROM pets                  WHERE created_by     = @wid
UNION ALL SELECT 'pets.owner_id',               COUNT(*) FROM pets                  WHERE owner_id       = @wid
UNION ALL SELECT 'pet_photos.uploaded_by',      COUNT(*) FROM pet_photos            WHERE uploaded_by    = @wid
UNION ALL SELECT 'applications.applicant_id',   COUNT(*) FROM adoption_applications WHERE applicant_id   = @wid
UNION ALL SELECT 'applications.reviewed_by',    COUNT(*) FROM adoption_applications WHERE reviewed_by    = @wid
UNION ALL SELECT 'adoptions.adopter_id',        COUNT(*) FROM adoptions             WHERE adopter_id     = @wid
UNION ALL SELECT 'boarding.owner_id',           COUNT(*) FROM boarding_requests     WHERE owner_id       = @wid
UNION ALL SELECT 'boarding.reviewed_by',        COUNT(*) FROM boarding_requests     WHERE reviewed_by    = @wid
UNION ALL SELECT 'boarding_updates.posted_by',  COUNT(*) FROM boarding_updates      WHERE posted_by      = @wid
UNION ALL SELECT 'care_tasks.assigned_to',      COUNT(*) FROM care_tasks            WHERE assigned_to    = @wid
UNION ALL SELECT 'care_tasks.completed_by',     COUNT(*) FROM care_tasks            WHERE completed_by   = @wid
UNION ALL SELECT 'care_logs.logged_by',         COUNT(*) FROM care_logs             WHERE logged_by      = @wid
UNION ALL SELECT 'vaccinations.recorded_by',    COUNT(*) FROM vaccinations          WHERE recorded_by    = @wid
UNION ALL SELECT 'attendance.checked_in_by',    COUNT(*) FROM daycare_attendance    WHERE checked_in_by  = @wid
UNION ALL SELECT 'attendance.checked_out_by',   COUNT(*) FROM daycare_attendance    WHERE checked_out_by = @wid
UNION ALL SELECT 'subscriptions.owner_id',      COUNT(*) FROM daycare_subscriptions WHERE owner_id       = @wid
UNION ALL SELECT 'billing_slips.owner_id',      COUNT(*) FROM billing_slips         WHERE owner_id       = @wid
UNION ALL SELECT 'quiz_questions.created_by',   COUNT(*) FROM quiz_questions        WHERE created_by     = @wid
ORDER BY rows_held DESC, reference;

-- The one-line version of the same question:
SELECT
  (SELECT COUNT(*) FROM pets                  WHERE created_by     = @wid)
+ (SELECT COUNT(*) FROM pets                  WHERE owner_id       = @wid)
+ (SELECT COUNT(*) FROM pet_photos            WHERE uploaded_by    = @wid)
+ (SELECT COUNT(*) FROM adoption_applications WHERE applicant_id   = @wid)
+ (SELECT COUNT(*) FROM adoption_applications WHERE reviewed_by    = @wid)
+ (SELECT COUNT(*) FROM adoptions             WHERE adopter_id     = @wid)
+ (SELECT COUNT(*) FROM boarding_requests     WHERE owner_id       = @wid)
+ (SELECT COUNT(*) FROM boarding_requests     WHERE reviewed_by    = @wid)
+ (SELECT COUNT(*) FROM boarding_updates      WHERE posted_by      = @wid)
+ (SELECT COUNT(*) FROM care_tasks            WHERE assigned_to    = @wid)
+ (SELECT COUNT(*) FROM care_tasks            WHERE completed_by   = @wid)
+ (SELECT COUNT(*) FROM care_logs             WHERE logged_by      = @wid)
+ (SELECT COUNT(*) FROM vaccinations          WHERE recorded_by    = @wid)
+ (SELECT COUNT(*) FROM daycare_attendance    WHERE checked_in_by  = @wid)
+ (SELECT COUNT(*) FROM daycare_attendance    WHERE checked_out_by = @wid)
+ (SELECT COUNT(*) FROM daycare_subscriptions WHERE owner_id       = @wid)
+ (SELECT COUNT(*) FROM billing_slips         WHERE owner_id       = @wid)
+ (SELECT COUNT(*) FROM quiz_questions        WHERE created_by     = @wid)
  AS total_blocking;


-- =============================================================================
-- STEP 3 -- DEACTIVATE.  *** THIS IS THE ONE YOU WANT ***
--
-- Works whatever STEP 2 said. Takes effect on the worker's very next request:
-- JwtAuthFilter loads the row and checks isEnabled() every time, so a token
-- they already hold stops working immediately.
--
-- What it does NOT do, deliberately:
--   - does not touch approval_status. `is_active` is "switched off";
--     approval_status is "was let in". A leaver is APPROVED and inactive.
--     Conflating them is how a rehire looks like a rejected applicant.
--   - does not clear shelter_id -- ck_users_staff_shelter REQUIRES a STAFF row
--     to have one, so nulling it while role is still 'STAFF' is refused.
--   - leaves their name on their work, which is the point.
--
-- Side effects that are already handled: CareService will not auto-assign them
-- new tasks, NotificationService will not email them, and HealthReminderJob
-- skips them -- all three filter on `is_active = TRUE`.
-- =============================================================================
UPDATE users SET is_active = FALSE WHERE id = @wid;

-- Log out every session they have open. Optional but usually wanted --
-- fk_user_tokens_user is ON DELETE CASCADE, so these go on their own if the
-- user is ever deleted; this just does it now.
DELETE FROM user_tokens WHERE user_id = @wid;

-- Check:
SELECT id, full_name, email, role, approval_status, is_active FROM users WHERE id = @wid;

-- To undo:  UPDATE users SET is_active = TRUE WHERE id = @wid;


-- =============================================================================
-- STEP 4 -- HARD DELETE.  *** ONLY IF total_blocking = 0 ***
--
-- If STEP 2 returned anything above zero, MySQL will refuse this with
-- "Cannot delete or update a parent row: a foreign key constraint fails".
-- That refusal is correct. Do not go hunting for the rows to delete first --
-- deleting a vaccination so you can delete the nurse is destroying a pet's
-- medical record to tidy a staff list.
--
-- WHAT GOES WITH THEM (ON DELETE CASCADE, automatic, not recoverable):
--   favorites, quiz_attempts, notifications, user_tokens
--
-- WHAT SURVIVES WITH THE NAME BLANKED (ON DELETE SET NULL):
--   audit_log.actor_id, billing_slips.issued_by, boarding_charges.added_by,
--   reports.generated_by
--
-- Note the asymmetry, and that it is on purpose: an audit row outlives the
-- person it describes, but it forgets who they were. A deleted worker leaves
-- anonymous entries in the admin's Recent Activity. One more reason STEP 3 is
-- the better answer.
-- =============================================================================
-- START TRANSACTION;
--
-- DELETE FROM users WHERE id = @wid AND role = 'STAFF';
--
-- -- Should say 1. If it says 0, the id was wrong or the account is not STAFF.
-- SELECT ROW_COUNT() AS deleted;
--
-- COMMIT;
-- -- (ROLLBACK; instead, if the count surprised you.)


-- -----------------------------------------------------------------------------
-- STEP 5 -- CONFIRM.
-- -----------------------------------------------------------------------------
SELECT  (SELECT COUNT(*) FROM users      WHERE id = @wid)                    AS user_row_still_there,
        (SELECT COUNT(*) FROM users      WHERE id = @wid AND is_active)      AS still_active,
        (SELECT COUNT(*) FROM user_tokens WHERE user_id = @wid)              AS open_sessions;

-- After STEP 3 (deactivate): 1, 0, 0
-- After STEP 4 (delete):     0, 0, 0


-- =============================================================================
-- VERIFIED. Every statement above was run against a real MySQL 8 loaded with
-- this project's schema through V19, and every claim in the comments was
-- tested rather than assumed:
--
--   STEP 2   the long form and the one-line total agree (5 references, 7 rows)
--   STEP 4   refused for a worker who had done work, with exactly the error
--            quoted:  ERROR 1451 ... CONSTRAINT `fk_attendance_in_by`
--   STEP 4   succeeded for a worker with total_blocking = 0 (ROW_COUNT 1)
--   STEP 3   is_active flips to 0, tokens go, the row stays  ->  STEP 5: 1,0,0
--   CASCADE  a deleted user's notifications and favorites went with them (1->0)
--   SET NULL the audit_log row SURVIVED the delete with actor_id = NULL
--   the shelter_id note: UPDATE users SET shelter_id = NULL on a STAFF row is
--            refused -- ERROR 3819, ck_users_staff_shelter
-- =============================================================================
