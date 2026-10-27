-- ===========================================================================
-- PawHome — TRANSACTIONS AND LOCKS
-- ===========================================================================
--
-- 150 @Transactional methods — 63 read-only, 87 read-write.
-- 6 queries take a pessimistic row lock.
-- 
-- WHY TRANSACTIONS ARE USED HERE. Two jobs, and only two.
-- 
-- 1. ALL OR NONE. Several writes that must land together or not at all.
--    Approving an adoption application closes every rival application, marks
--    the pet adopted, notifies the applicant and writes an audit row.
--    Checking a pet out of boarding computes the nights actually stayed,
--    copies the accrued charges onto a slip, issues it and frees the room.
-- 
-- 2. RULES A CHECK CONSTRAINT CANNOT SEE. A CHECK constraint sees ONE ROW.
--    "This room is not full" and "this pet is not already booked" are
--    statements about OTHER rows, and MySQL cannot express them
--    declaratively at all. Those rules are enforced instead by a transaction
--    that takes a pessimistic lock, reads the other rows, decides, and
--    writes. The six locks below are that mechanism.
-- 
-- WHY THE LOCK IS NECESSARY, precisely. The isolation level is REPEATABLE
-- READ. Without FOR UPDATE, the second transaction reads a snapshot taken
-- BEFORE the first one wrote. That snapshot is perfectly consistent — it is
-- simply out of date, which is still wrong when you are about to write based
-- on it. A lock taken and released before the caller has decided has
-- protected nothing, so every caller of these queries is @Transactional.
-- 
-- The audit writer deliberately has NO @Transactional of its own: it joins
-- the caller's transaction. An audit row for an approval that rolled back
-- would record something that never happened.


-- ###########################################################################
-- #  THE SIX LOCKED QUERIES
-- ###########################################################################

------------------------------------------------------------------------------
-- [LOCK] DaycareSubscriptionRepository.lockPetSlot
-- FILE: daycare/DaycareSubscriptionRepository.java
-- @Lock(LockModeType.PESSIMISTIC_WRITE)  — appends FOR UPDATE
------------------------------------------------------------------------------
-- One pet cannot hold two subscriptions to the same daycare time band.
-- Without the lock both inserts read "no rows" and both succeed.

SELECT s FROM DaycareSubscription s
 WHERE s.petId  = :petId
   AND s.slot   = :slot
   AND s.status = com.pawhome.backend.daycare.SubscriptionStatus.ACTIVE


------------------------------------------------------------------------------
-- [LOCK] DaycareSubscriptionRepository.lockSlotAtShelter
-- FILE: daycare/DaycareSubscriptionRepository.java
-- @Lock(LockModeType.PESSIMISTIC_WRITE)  — appends FOR UPDATE
------------------------------------------------------------------------------
-- Counts the subscriptions already sold for a shelter's time band, held
-- while the caller decides whether one more fits inside the band's capacity.

SELECT s FROM DaycareSubscription s
 WHERE s.shelterId = :shelterId
   AND s.slot      = :slot
   AND s.status    = com.pawhome.backend.daycare.SubscriptionStatus.ACTIVE
 ORDER BY s.id


------------------------------------------------------------------------------
-- [LOCK] DaycareSubscriptionRepository.lockPetSubscriptionsOver
-- FILE: daycare/DaycareSubscriptionRepository.java
-- @Lock(LockModeType.PESSIMISTIC_WRITE)  — appends FOR UPDATE
------------------------------------------------------------------------------
-- A pet with daycare time subscribed over a date range must not also be
-- booked into boarding over the same range. This is the daycare side of the
-- clash; lockOverlappingForPet closes the boarding side.

SELECT s FROM DaycareSubscription s
 WHERE s.petId  = :petId
   AND s.status = com.pawhome.backend.daycare.SubscriptionStatus.ACTIVE
   AND s.startDate <= :end
   AND (s.endDate IS NULL OR s.endDate >= :start)
 ORDER BY s.id


------------------------------------------------------------------------------
-- [LOCK] ShelterRepository.lockById
-- FILE: shelter/ShelterRepository.java
-- @Lock(LockModeType.PESSIMISTIC_WRITE)  — appends FOR UPDATE
------------------------------------------------------------------------------
-- Locks the shelter row while the service decides whether the BUILDING has
-- room. Nothing here writes to shelters — the row is used purely as the
-- thing to serialise on, so that two acceptances for the same shelter cannot
-- both read the same free-places count.

SELECT s FROM Shelter s WHERE s.id = :id


------------------------------------------------------------------------------
-- [LOCK] BoardingRequestRepository.lockOverlappingForPet
-- FILE: boarding/BoardingRequestRepository.java
-- @Lock(LockModeType.PESSIMISTIC_WRITE)  — appends FOR UPDATE
------------------------------------------------------------------------------
-- THE QUERY THAT MAKES DOUBLE-BOOKING IMPOSSIBLE. Two staff accepting
-- overlapping stays for one animal in the same second would both read an
-- empty result and both write. FOR UPDATE makes the second transaction block
-- on the first's row locks until it commits — and then it sees what the
-- first one wrote, and refuses. The overlap test is the pair of
-- inequalities, not BETWEEN: two closed ranges overlap when each starts on
-- or before the other ends, and BETWEEN would miss a stay that straddles the
-- new dates entirely.

SELECT b FROM BoardingRequest b
 WHERE b.petId = :petId
   AND b.status IN (com.pawhome.backend.boarding.BoardingStatus.ACCEPTED,
                    com.pawhome.backend.boarding.BoardingStatus.ACTIVE)
   AND b.startDate <= :end
   AND b.endDate   >= :start
 ORDER BY b.startDate


------------------------------------------------------------------------------
-- [LOCK] BoardingRequestRepository.lockOverlappingForSuite
-- FILE: boarding/BoardingRequestRepository.java
-- @Lock(LockModeType.PESSIMISTIC_WRITE)  — appends FOR UPDATE
------------------------------------------------------------------------------
-- The same guarantee for the ROOM rather than the animal. Without it,
-- assigning a suite is decoration: two dogs would be booked into Kennel 1
-- for the same week and nothing anywhere would object, because
-- shelter_suites has no notion of a date.

SELECT b FROM BoardingRequest b
 WHERE b.suiteId = :suiteId
   AND b.status IN (com.pawhome.backend.boarding.BoardingStatus.ACCEPTED,
                    com.pawhome.backend.boarding.BoardingStatus.ACTIVE)
   AND b.startDate <= :end
   AND b.endDate   >= :start


-- ###########################################################################
-- #  EVERY @Transactional METHOD, BY FILE
-- #  (RO = read-only, RW = read-write)
-- ###########################################################################

-- admin/AdminUserService.java
--     RW  approve
--     RO  counts
--     RW  forceVerify
--     RO  list
--     RW  reject
--     RW  resendVerification
--     RW  setActive
--     RW  setShelter
--
-- admin/InviteService.java
--     RW  accept
--     RW  invite
--     RW  resendInvite
--
-- analytics/AnalyticsService.java
--     RO  overview
--
-- application/ApplicationService.java
--     RW  apply
--     RW  approve
--     RO  counts
--     RW  decline
--     RO  forShelter
--     RO  get
--     RO  mine
--     RW  withdraw
--
-- audit/AuditService.java
--     RO  recent
--
-- auth/AuthService.java
--     RO  login
--     RW  register
--
-- auth/VerificationService.java
--     RW  requestPasswordReset
--     RW  resendVerification
--     RW  resetPassword
--     RW  verifyEmail
--
-- billing/BillingService.java
--     RO  detail
--     RO  forShelter
--     RO  get
--     RW  issueForBoarding
--     RW  issueForSubscription
--     RO  mine
--     RO  totals
--
-- billing/MonthlyBillingJob.java
--     RW  daycare
--
-- boarding/BoardingRequestRepository.java
--     RW  lockOverlappingForPet
--
-- boarding/BoardingService.java
--     RW  accept
--     RW  addCharge
--     RW  cancel
--     RW  checkIn
--     RW  complete
--     RO  counts
--     RW  decline
--     RO  detail
--     RO  forShelter
--     RO  mine
--     RW  moveSuite
--     RW  postUpdate
--     RW  removeCharge
--     RW  request
--     RO  suitesFor
--
-- care/CareDueJob.java
--     RW  announceDueTasks
--
-- care/CareService.java
--     RO  board
--     RO  colleagues
--     RW  complete
--     RO  counts
--     RW  create
--     RW  delete
--     RW  logCare
--     RO  logs
--     RW  skip
--     RO  tasks
--     RW  update
--
-- daycare/DaycareService.java
--     RO  board
--     RW  cancel
--     RW  checkIn
--     RW  checkOut
--     RO  dueToday
--     RO  forShelter
--     RO  get
--     RO  housingPlaces
--     RO  mine
--     RO  occupancy
--     RO  offers
--     RW  retireEnded
--     RW  subscribe
--
-- daycare/DaycareSubscriptionRepository.java
--     RW  lockSlotAtShelter
--
-- health/VaccinationService.java
--     RW  add
--     RW  delete
--     RO  forPet
--     RO  forShelter
--     RW  setCertificate
--     RO  typesFor
--     RW  update
--
-- mail/HealthReminderJob.java
--     RW  sendDueReminders
--
-- notification/NotificationService.java
--     RO  inbox
--     RW  markAllRead
--     RW  markRead
--
-- pet/FavoriteService.java
--     RW  add
--     RO  mine
--     RW  remove
--
-- pet/PetService.java
--     RW  addMyPhoto
--     RW  addPhoto
--     RO  browse
--     RO  counts
--     RW  create
--     RW  delete
--     RW  deleteMyPhoto
--     RW  deletePhoto
--     RO  favorites
--     RO  get
--     RO  list
--     RO  myPetDetail
--     RO  myPets
--     RO  publicPet
--     RW  registerMyPet
--     RW  removeMyPet
--     RW  setCover
--     RW  setMyCover
--     RW  setStatus
--     RW  update
--     RW  updateMyPet
--
-- quiz/QuizService.java
--     RO  counts
--     RW  create
--     RO  get
--     RO  list
--     RW  reorder
--     RW  setStatus
--     RO  traits
--     RW  update
--
-- quiz/QuizTakingService.java
--     RO  latest
--     RO  myTraits
--     RO  questions
--     RW  submit
--
-- report/ReportService.java
--     RW  delete
--     RO  download
--     RW  generate
--     RO  list
--
-- shelter/ShelterService.java
--     RW  addMySuite
--     RW  addSuite
--     RW  create
--     RW  deactivateMySuite
--     RW  deactivateSuite
--     RO  get
--     RO  list
--     RO  mine
--     RO  myRooms
--     RO  mySuites
--     RO  options
--     RW  setLogo
--     RW  setStatus
--     RO  suites
--     RW  update
--     RW  updateMySuite
--     RW  updateSuite
--     RO  whoIsHere
--
-- token/TokenService.java
--     RW  issue
--     RW  purgeExpired
--     RW  redeem
--     RO  wasIssuedButUnusable
--
-- user/MeService.java
--     RW  changePassword
--     RW  setAvatar
--     RW  setPreferences
--     RW  updateProfile
--

-- ###########################################################################
-- #  TEN WORKED EXAMPLES — WHAT ONE @Transactional ACTUALLY RUNS
-- ###########################################################################
--
-- @Transactional WRITES NO SQL OF ITS OWN. It is an instruction to Spring:
-- put a BEGIN before this method and a COMMIT after it. The queries are what
-- goes INSIDE that wrapper. Each example below shows one annotated method as
-- the sequence of statements the database actually sees.
--
-- Read them as: container and contents. If any exception escapes the method,
-- Spring issues ROLLBACK instead of COMMIT, and every line between the two
-- is undone.
--
-- LOCK marks a SELECT ... FOR UPDATE. Its row locks are held until the
-- COMMIT, which is the whole point: a lock released before the code has
-- decided has protected nothing. That is why these methods must be
-- transactional, and why the lock and the annotation are two halves of one
-- mechanism rather than two separate features.
--
-- An INSERT or UPDATE with no query text beside it is generated by Hibernate
-- from the entity class when the code calls save(); it does not appear
-- anywhere in the source.


-- ===========================================================================
-- EXAMPLE 1.  ApplicationService.approve(id, request)
-- API: POST /api/v1/staff/applications/{id}/approve
-- ===========================================================================
--
-- The largest transaction in the system, and the clearest argument for
-- having one. Nine operations. If the INSERT INTO adoptions succeeded and
-- then the UPDATE on pets failed, there would be an adoption record for a
-- pet still listed as AVAILABLE — and a rival applicant could apply for an
-- animal that is already gone.
--
BEGIN;          -- @Transactional opens it

    SELECT adoption_applications      -- load(id)
        -- the application being decided
    SELECT pets                       -- petRepository.findById
        -- and its pet
    -- guards: the application must be PENDING, the pet AVAILABLE or PENDING,
    -- and the adoption cannot be dated in the future
    UPDATE adoption_applications      -- markApproved
        -- status APPROVED, reviewer, timestamp
    -- markApproved returns 0 if somebody else already decided it: that
    -- raises 409 and the whole transaction rolls back
    INSERT adoptions                  -- adoptionRepository.save
        -- the agreement itself
    UPDATE pets                       -- markAdopted
        -- status ADOPTED, owner_id set
    SELECT adoption_applications      -- findByPetIdAndStatus
        -- the rival applications
    UPDATE adoption_applications      -- closeRivalApplications
        -- ALL rivals, one statement
    INSERT notifications              -- one per rejected rival, plus one for the winner
    INSERT audit_log                  -- APPLICATION_APPROVED

COMMIT;         -- or ROLLBACK, if anything above threw


-- ===========================================================================
-- EXAMPLE 2.  ApplicationService.decline(id, request)
-- API: POST /api/v1/staff/applications/{id}/decline
-- ===========================================================================
--
-- The same shape, smaller. Worth showing beside approve because the pet is
-- NOT touched: declining one application changes nothing about the animal,
-- so no UPDATE on pets appears.
--
BEGIN;          -- @Transactional opens it

    SELECT adoption_applications      -- load(id)
    SELECT pets                       -- only to name the pet in the message
    UPDATE adoption_applications      -- markDeclined
        -- status, reviewer, reason code, message
    INSERT notifications              -- the applicant is told, with the reason
    INSERT audit_log                  -- APPLICATION_DECLINED

COMMIT;         -- or ROLLBACK, if anything above threw


-- ===========================================================================
-- EXAMPLE 3.  BoardingService.accept(id, request)
-- API: POST /api/v1/staff/boarding/{id}/accept
-- ===========================================================================
--
-- FIVE CAPACITY AND ELIGIBILITY CHECKS BEFORE ANYTHING IS WRITTEN, three of
-- them holding locks. This is the method the transaction exists for: every
-- check reads rows that another staff member could be changing at the same
-- moment, and the locks are what stop the two of them both passing.
--
BEGIN;          -- @Transactional opens it

    SELECT boarding_requests          -- load(id)
        -- the request, which must be REQUESTED
    SELECT pets                       -- the animal
    SELECT ... FROM boarding_requests      FOR UPDATE;
        -- lockOverlappingForPet — CHECK 1: is this pet already booked over these dates?
    SELECT ... FROM daycare_subscriptions  FOR UPDATE;
        -- lockPetSubscriptionsOver — CHECK 2: does it have daycare time subscribed over them?
    SELECT ... FROM shelters               FOR UPDATE;
        -- lockById — CHECK 3 holds the shelter row while the building's free places are counted
    SELECT boarding_requests          -- countPlacesHeld
        -- places already committed for these dates
    SELECT shelter_suites             -- the chosen room, if one was named
    SELECT ... FROM boarding_requests      FOR UPDATE;
        -- lockOverlappingForSuite — CHECK 4: is the ROOM free, and CHECK 5: does it take this species?
    UPDATE boarding_requests          -- save
        -- status ACCEPTED, suite assigned, rate copied
    INSERT notifications              -- the owner is told
    INSERT audit_log                  -- BOARDING_ACCEPTED

COMMIT;         -- or ROLLBACK, if anything above threw


-- ===========================================================================
-- EXAMPLE 4.  BoardingService.complete(id, request)
-- API: POST /api/v1/staff/boarding/{id}/complete
-- ===========================================================================
--
-- Checking a pet out also bills it. The nights charged are the nights
-- ACTUALLY stayed, computed here — which is why the slip cannot be issued in
-- advance. Six writes across five tables, and a half-finished check-out
-- would leave a pet marked BOARDING with a bill already raised.
--
BEGIN;          -- @Transactional opens it

    SELECT boarding_requests          -- load(id)
        -- must be ACTIVE
    SELECT pets                       -- the animal
    -- picked_up_on is clamped to start_date if the stay was checked in
    -- early, because ck_boarding_picked_up refuses collection before arrival
    UPDATE boarding_requests          -- save
        -- status COMPLETED, picked_up_on, suite released
    UPDATE pets                       -- save
        -- status back to ADOPTED or OWNED
    INSERT boarding_updates           -- writeUpdate
        -- a DEPARTURE entry on the stay timeline
    SELECT billing_slips              -- findForBoarding
        -- uq_slip_boarding allows only one slip per stay, so this
        -- returns early if it exists
    INSERT billing_slips              -- the bill: nights stayed x the rate copied at booking
    INSERT billing_slip_items         -- writeLines
        -- the accrued boarding_charges, frozen onto the slip
    INSERT notifications              -- SLIP_ISSUED
    INSERT audit_log                  -- BOARDING_COMPLETED

COMMIT;         -- or ROLLBACK, if anything above threw


-- ===========================================================================
-- EXAMPLE 5.  BoardingService.moveSuite(id, request)
-- API: POST /api/v1/staff/boarding/{id}/suite
-- ===========================================================================
--
-- Moving a pet between rooms mid-stay. The capacity check on the NEW room
-- has to hold its lock until the move is written, or two pets could be moved
-- into the last free place at once.
--
BEGIN;          -- @Transactional opens it

    SELECT boarding_requests          -- load(id)
        -- must be ACCEPTED or ACTIVE
    SELECT shelter_suites             -- the destination room; must be active and take this species
    SELECT pets                       -- to check the species
    SELECT ... FROM boarding_requests      FOR UPDATE;
        -- lockOverlappingForSuite — is there room in the destination for the rest of the stay?
    UPDATE boarding_requests          -- save
        -- suite_id changed
    INSERT boarding_updates           -- a ROOM_CHANGE entry, so the owner can be told when and why (the value appended to the ENUM in V18)
    INSERT notifications              -- the owner is told

COMMIT;         -- or ROLLBACK, if anything above threw


-- ===========================================================================
-- EXAMPLE 6.  DaycareService.subscribe(request)
-- API: POST /api/v1/daycare/subscriptions
-- ===========================================================================
--
-- THREE LOCKS IN ONE TRANSACTION. A subscription sells a time band, and a
-- band has a capacity, so the same race exists as for boarding — plus a
-- third rule: a pet must not hold two subscriptions to the same band.
--
BEGIN;          -- @Transactional opens it

    SELECT pets                       -- the pet, which the caller must own
    SELECT shelters                   -- the shelter, which must run daycare (price and capacity > 0)
    SELECT quiz_attempts              -- requirePassedBoardingAttempt
        -- the owner must have passed the boarding quiz
    SELECT ... FROM boarding_requests      FOR UPDATE;
        -- lockOverlappingForPet — the pet must not be booked into boarding over the same dates
    SELECT ... FROM daycare_subscriptions  FOR UPDATE;
        -- lockPetSlot — this pet must not already hold this band
    SELECT ... FROM daycare_subscriptions  FOR UPDATE;
        -- lockSlotAtShelter — count what is sold for the band, against its capacity
    INSERT daycare_subscriptions      -- save
        -- the subscription, monthly price copied

COMMIT;         -- or ROLLBACK, if anything above threw


-- ===========================================================================
-- EXAMPLE 7.  DaycareService.checkIn(request)
-- API: POST /api/v1/staff/daycare/attendance
-- ===========================================================================
--
-- The room capacity check for daycare. countPresentInSuiteBand is read while
-- lockOverlappingForSuite is held, because the same room may also hold
-- boarding animals — the two services share the rooms, so they must share
-- the lock.
--
BEGIN;          -- @Transactional opens it

    SELECT daycare_subscriptions      -- load(id)
        -- must be ACTIVE and cover today
    SELECT daycare_attendance         -- findVisit
        -- uq_visit_per_day means one visit per subscription per day; a
        -- second check-in is refused
    SELECT shelter_suites             -- the room
    SELECT pets                       -- to check the room takes this species
    SELECT ... FROM boarding_requests      FOR UPDATE;
        -- lockOverlappingForSuite — boarding animals occupy the same room
    SELECT daycare_attendance         -- countPresentInSuiteBand
        -- how many are already in this room, this date, this band, not
        -- yet gone
    INSERT daycare_attendance         -- save
        -- checked_in_at, checked_in_by

COMMIT;         -- or ROLLBACK, if anything above threw


-- ===========================================================================
-- EXAMPLE 8.  DaycareService.checkOut(attendanceId)
-- API: POST /api/v1/staff/daycare/attendance/{id}/checkout
-- ===========================================================================
--
-- Shown deliberately as a CONTRAST. Two operations, no locks, nothing to co-
-- ordinate — the room is being freed, not filled, so no capacity rule
-- applies. Not every transaction needs to be elaborate; this one is still a
-- transaction because the UPDATE must either land or not.
--
BEGIN;          -- @Transactional opens it

    SELECT daycare_attendance         -- findById
        -- must exist and still be on site
    UPDATE daycare_attendance         -- checkOut
        -- checked_out_at, checked_out_by

COMMIT;         -- or ROLLBACK, if anything above threw


-- ===========================================================================
-- EXAMPLE 9.  BillingService.issueForSubscription(sub, month)
-- API: SCHEDULED JOB — MonthlyBillingJob.billTheMonth
-- ===========================================================================
--
-- No user triggers this. The guard on the first line is what makes the
-- monthly job safe to run twice: uq_slip_period is UNIQUE on
-- (subscription_id, period_from), so a second run for the same month finds
-- the existing slip and returns it rather than billing the customer again.
--
BEGIN;          -- @Transactional opens it

    SELECT billing_slips              -- findForSubscriptionMonth
        -- already billed for this month? then stop here and return it
    INSERT billing_slips              -- save
        -- one month, the price copied from the subscription
    INSERT notifications              -- SLIP_ISSUED to the owner

COMMIT;         -- or ROLLBACK, if anything above threw


-- ===========================================================================
-- EXAMPLE 10.  InviteService.accept(token, password)
-- API: POST /api/v1/auth/accept-invite
-- ===========================================================================
--
-- Redeeming a single-use token. The token is marked used and the account is
-- activated in ONE transaction, so a crash between the two cannot leave a
-- token spent on an account that never got its password.
--
BEGIN;          -- @Transactional opens it

    SELECT user_tokens                -- findByTokenHash
        -- the SHA-256 of the token, never the token itself
    -- the token must be of type INVITE, unused, and not expired
    UPDATE user_tokens                -- save
        -- used_at stamped, so it cannot be redeemed twice
    SELECT users                      -- findById
        -- the invited account
    UPDATE users                      -- save
        -- password hash set, email_verified, approved, active

COMMIT;         -- or ROLLBACK, if anything above threw


-- ===========================================================================
-- A NOTE ON readOnly = true
-- ===========================================================================
--
-- 63 of the methods are @Transactional(readOnly = true). They still get a
-- transaction — the annotation does not turn one off. It tells Hibernate not
-- to bother tracking changes to the entities it loads, and lets the driver
-- flag the connection as read-only. The practical effect is a cheaper read,
-- not a different kind of unit of work.
--
-- AND ONE DELIBERATE ABSENCE. AuditService.record has NO @Transactional of
-- its own. It joins whatever transaction is already running. If it opened
-- its own it would commit separately, and the audit log could end up
-- recording an approval that rolled back — a record of something that never
-- happened.
