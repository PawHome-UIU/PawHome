-- =============================================================================
-- V13__daycare_and_billing_slips.sql
--
-- BOARDING GETS A BILL, AND DAYCARE GETS A PRODUCT.
--
-- Two things are added here, and they share one table because they are the
-- same document:
--
--   1. PER-NIGHT BOARDING already worked end to end -- request, accept, check
--      in, complete -- and then simply stopped. The stay finished and nothing
--      recorded what it cost. complete() now issues a slip.
--
--   2. DAYCARE SUBSCRIPTION is new. An owner leaves the pet for ONE fixed slot
--      every day, ongoing, and is billed monthly. Three slots: 09:00-12:00,
--      12:00-15:00, 15:00-18:00.
--
-- WHAT THIS IS NOT. There is no payment processing here. No gateway, no card
-- storage, no payments table, no PENDING/PAID state machine. The system
-- records WHAT IS OWED on a slip and stops; the money is handed over in cash
-- at the counter. That was a deliberate scope decision, not an omission --
-- a payments subsystem was designed and then dropped, because the brief is
-- "generate a payslip" and a PAID flag is the first step back into being a
-- payment system by accident. If settlement tracking is ever genuinely wanted,
-- it is a new migration and this paragraph is the record of why it was not
-- done now.
--
-- 27 tables -> 29.
-- =============================================================================


-- =============================================================================
-- 1. TWO COLUMNS ON shelters -- NOT A PRICING TABLE
-- =============================================================================
--
-- shelters.capacity ALREADY EXISTS AND IS NOT REUSED. It counts kennel places
-- for resident animals -- the denominator of the "28 / 50" bar on the shelter
-- page. A daycare slot is a different number about a different thing (a pet
-- that goes home at 12:00 occupies no kennel), and conflating the two would
-- make one of the two figures wrong on screen.
--
-- ALL THREE SLOTS COST THE SAME under this design. Per-slot pricing -- morning
-- dearer than midday -- would need a third table, shelter_slot_prices
-- (shelter_id, slot, price). It is not worth a table until the difference is
-- real, and nothing below assumes a single price, so adding it later changes
-- this column into that table without touching billing_slips.

ALTER TABLE shelters
    ADD COLUMN daycare_monthly_price DECIMAL(10,2) NOT NULL DEFAULT 0.00
        COMMENT 'Current price of one daily slot for one month. Copied onto new subscriptions.'
        AFTER boarding_rate_daily,
    ADD COLUMN daycare_slot_capacity INT NOT NULL DEFAULT 20
        COMMENT 'How many pets may hold the same slot at the same time.'
        AFTER daycare_monthly_price;

ALTER TABLE shelters
    ADD CONSTRAINT ck_shelters_daycare_price
        CHECK (daycare_monthly_price >= 0),
    -- Zero is allowed and means "we do not run daycare", which is different
    -- from a negative number, which means somebody typed into the wrong box.
    ADD CONSTRAINT ck_shelters_daycare_capacity
        CHECK (daycare_slot_capacity >= 0);


-- =============================================================================
-- 2. ONE COLUMN ON boarding_requests -- WHEN THE PET ACTUALLY LEFT
-- =============================================================================
--
-- THE BUG THIS PREVENTS. Until now checkIn() and complete() moved the status
-- and stamped no date, so the only dates anywhere were the BOOKED ones. A slip
-- built from those bills five nights to somebody who collected their dog on
-- the third morning, and the customer is standing at the counter holding the
-- proof. Billing what was booked is a policy some businesses choose; billing
-- what was booked while CALLING it what the pet stayed is just wrong.
--
-- So complete() stamps the collection date and the slip is built from it.
--
-- NULLABLE, because it is only true of a stay that has ended. A REQUESTED or
-- ACTIVE row has no collection date and must not pretend to.

ALTER TABLE boarding_requests
    ADD COLUMN picked_up_on DATE NULL
        COMMENT 'Day the pet was actually collected. Set by complete(); NULL until the stay ends. The slip bills start_date -> this.'
        AFTER end_date;

ALTER TABLE boarding_requests
    -- Collected before it arrived is not a late collection, it is a bad row.
    -- There is deliberately NO upper bound: a pet collected after end_date is
    -- a real thing that happens, and the whole point of this column is that
    -- the slip can charge for it.
    ADD CONSTRAINT ck_boarding_picked_up
        CHECK (picked_up_on IS NULL OR picked_up_on >= start_date);


-- =============================================================================
-- 3. daycare_subscriptions
-- =============================================================================

CREATE TABLE daycare_subscriptions (
    id            BIGINT NOT NULL AUTO_INCREMENT,

    pet_id        BIGINT NOT NULL,
    -- Must equal pets.owner_id at signup, checked in the service for the same
    -- reason boarding_requests.owner_id is: a CHECK cannot read another table.
    -- Stored rather than derived so a pet later rehomed does not rewrite the
    -- history of who signed it up.
    owner_id      BIGINT NOT NULL,
    shelter_id    BIGINT NOT NULL,

    -- FIXED BANDS, NOT ADMIN-EDITABLE. Editable slot times would mean a third
    -- table (shelter_slots) and a join on every capacity check, to make
    -- configurable something no shelter in the brief wants to configure. If
    -- that ever changes, this ENUM becomes an FK and this comment is the
    -- warning that the change is bigger than it looks.
    slot          ENUM('MORNING','MIDDAY','AFTERNOON') NOT NULL
                  COMMENT 'MORNING 09:00-12:00, MIDDAY 12:00-15:00, AFTERNOON 15:00-18:00',

    -- 3NF EXCEPTION, JUSTIFIED -- the same one boarding_requests.daily_rate
    -- already makes. shelters.daycare_monthly_price is what the shelter
    -- charges TODAY; this is what THIS customer agreed to. An admin raising
    -- the price must not silently re-bill somebody who subscribed last year,
    -- which is exactly what would happen if the monthly job joined the live
    -- price instead of reading this copy.
    monthly_price DECIMAL(10,2) NOT NULL,

    -- THE GATE. Daycare takes the same BOARDING quiz a per-night stay takes,
    -- and for a stronger reason: a subscription leaves the pet here every
    -- weekday indefinitely, so the questions about temperament, vaccination
    -- and emergency vet authority matter more, not less.
    --
    -- NOT NULL, unlike boarding_requests.quiz_attempt_id. That column is
    -- nullable because requests predating the quiz already existed when V6
    -- ran. There are no daycare subscriptions at all yet, so there is no
    -- history to accommodate and no reason to allow an ungated signup.
    quiz_attempt_id BIGINT NOT NULL,
    -- CONSTANT. Exists only so the foreign key below can be composite. See V6:
    -- a type-blind FK to quiz_attempts(id) would accept a PERSONALITY attempt
    -- as the gate, and the personality quiz has no pass mark -- a subscription
    -- approved against a quiz that cannot be failed.
    quiz_attempt_type ENUM('ELIGIBILITY','PERSONALITY','BOARDING')
                      NOT NULL DEFAULT 'BOARDING'
                      COMMENT 'Constant. Exists only so fk_sub_attempt can check the type.',

    start_date    DATE NOT NULL,
    -- NULL means ongoing with no agreed end. Set to the cancellation date when
    -- the owner stops, which is why it is not simply derived from status.
    end_date      DATE NULL,

    status        ENUM('ACTIVE','CANCELLED','EXPIRED') NOT NULL DEFAULT 'ACTIVE',
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),

    -- The capacity check reads exactly these three columns, in this order:
    -- shelter, slot, still active. Leftmost-prefix, so it is an index-only
    -- count -- which matters, because that count is taken under FOR UPDATE and
    -- every row it touches is locked until the transaction commits.
    KEY idx_sub_capacity (shelter_id, slot, status),
    -- "My subscriptions", and the monthly job's sweep of everything ACTIVE.
    KEY idx_sub_owner (owner_id, status),
    KEY idx_sub_pet (pet_id),
    KEY idx_sub_billing (status, start_date),

    CONSTRAINT fk_sub_pet     FOREIGN KEY (pet_id)
        REFERENCES pets (id)     ON DELETE RESTRICT,
    CONSTRAINT fk_sub_owner   FOREIGN KEY (owner_id)
        REFERENCES users (id)    ON DELETE RESTRICT,
    CONSTRAINT fk_sub_shelter FOREIGN KEY (shelter_id)
        REFERENCES shelters (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sub_attempt FOREIGN KEY (quiz_attempt_id, quiz_attempt_type)
        REFERENCES quiz_attempts (id, quiz_type) ON DELETE RESTRICT,

    CONSTRAINT ck_sub_quiz_type CHECK (quiz_attempt_type = 'BOARDING'),
    CONSTRAINT ck_sub_price     CHECK (monthly_price >= 0),
    CONSTRAINT ck_sub_dates     CHECK (end_date IS NULL OR end_date >= start_date),
    -- A row that has stopped must say when. The mirror half is deliberately
    -- absent: an ACTIVE subscription MAY carry an end_date, because agreeing
    -- up front to finish in June is a normal thing to do.
    CONSTRAINT ck_sub_ended     CHECK (status = 'ACTIVE' OR end_date IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- WHAT IS DELIBERATELY NOT A UNIQUE KEY: (pet_id, slot).
--
-- A pet may hold more than one slot -- morning AND afternoon is a reasonable
-- thing to want. The same slot twice is not, but a plain UNIQUE (pet_id, slot)
-- would also forbid re-subscribing to the morning slot after cancelling it,
-- which is the normal case. Expressing "at most one ACTIVE row per (pet, slot)"
-- needs the generated-column trick this schema already uses twice
-- (pets.pending_flag, pet_photos.cover_flag) and has been told is too clever.
-- This is a cheap place to be plain: the rule lives in DaycareService, stated
-- once, and this comment is where a reader is told to look for it.


-- =============================================================================
-- 4. billing_slips -- ONE TABLE FOR BOTH
-- =============================================================================
--
-- A slip is a slip: an owner, a shelter, a period, a quantity, a rate, a
-- total. Two near-identical tables would make every "show me my bills" query a
-- UNION, and every report a UNION of two UNIONs.

CREATE TABLE billing_slips (
    id                  BIGINT NOT NULL AUTO_INCREMENT,

    -- What the customer quotes down the phone. See the note below on why it is
    -- derived from the source row rather than counted.
    slip_no             VARCHAR(30) NOT NULL,

    owner_id            BIGINT NOT NULL,
    shelter_id          BIGINT NOT NULL,

    -- EXACTLY ONE SOURCE, AND BOTH ARE REAL FOREIGN KEYS.
    --
    -- The obvious alternative -- entity_type VARCHAR + entity_id BIGINT -- is
    -- what audit_log does, and audit_log is the anti-pattern being avoided
    -- here: it LOOKS like a foreign key and enforces nothing, so a slip can
    -- point at a boarding request that was never created. Two nullable columns
    -- cost one extra CHECK and buy referential integrity on both.
    boarding_request_id BIGINT NULL,
    subscription_id     BIGINT NULL,

    period_from  DATE NOT NULL,
    period_to    DATE NOT NULL,
    units        SMALLINT NOT NULL       COMMENT 'Nights boarded, or months subscribed.',
    unit_rate    DECIMAL(10,2) NOT NULL  COMMENT 'Per night, or per month.',
    total        DECIMAL(10,2) NOT NULL,

    issued_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- NULL means the monthly job issued it, not a person. ON DELETE SET NULL
    -- rather than RESTRICT: a staff member leaving must not be undeletable
    -- because they once printed a receipt, and the slip is still valid without
    -- a name on it.
    issued_by    BIGINT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uq_slip_no (slip_no),

    -- ONE SLIP PER COMPLETED STAY. This is what makes issuing idempotent: a
    -- double-clicked "Complete" collides on this key instead of billing the
    -- customer twice. MySQL lets a UNIQUE key hold many NULLs, so every
    -- subscription slip -- which leaves this column NULL -- is unaffected.
    UNIQUE KEY uq_slip_boarding (boarding_request_id),
    -- ONE SLIP PER SUBSCRIPTION PER MONTH. Makes the monthly job safe to re-run
    -- by hand, which somebody always ends up doing after it fails halfway.
    UNIQUE KEY uq_slip_period (subscription_id, period_from),

    -- "My slips, newest first" and the shelter's takings list. Both filter on
    -- one id and sort by date, which is exactly a two-column index.
    KEY idx_slip_owner (owner_id, issued_at),
    KEY idx_slip_shelter (shelter_id, issued_at),

    CONSTRAINT fk_slip_owner    FOREIGN KEY (owner_id)
        REFERENCES users (id)                 ON DELETE RESTRICT,
    CONSTRAINT fk_slip_shelter  FOREIGN KEY (shelter_id)
        REFERENCES shelters (id)              ON DELETE RESTRICT,
    CONSTRAINT fk_slip_boarding FOREIGN KEY (boarding_request_id)
        REFERENCES boarding_requests (id)     ON DELETE RESTRICT,
    CONSTRAINT fk_slip_sub      FOREIGN KEY (subscription_id)
        REFERENCES daycare_subscriptions (id) ON DELETE RESTRICT,
    CONSTRAINT fk_slip_issuer   FOREIGN KEY (issued_by)
        REFERENCES users (id)                 ON DELETE SET NULL,

    -- Exactly one source, never both, never neither.
    CONSTRAINT ck_slip_source CHECK (
        (boarding_request_id IS NOT NULL AND subscription_id IS     NULL) OR
        (boarding_request_id IS     NULL AND subscription_id IS NOT NULL)),
    CONSTRAINT ck_slip_period CHECK (period_to >= period_from),
    -- A nought-unit slip is a slip for nothing. A pet dropped off and collected
    -- the same morning is billed the one night minimum, which the service does
    -- explicitly rather than letting this constraint reject the row.
    CONSTRAINT ck_slip_units  CHECK (units > 0),
    CONSTRAINT ck_slip_total  CHECK (total >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- -----------------------------------------------------------------------------
-- WHY total IS STORED AND NOT COMPUTED
-- -----------------------------------------------------------------------------
-- A slip is a DOCUMENT, not a view. It must still say what it said on the day
-- it was issued, even after the shelter changes its rate -- the same reason
-- adoption_applications copies the applicant's address instead of joining to
-- users. What the customer was handed is the record; what the price happens to
-- be today is not.
--
-- There is deliberately NO CHECK that total = units * unit_rate. A prorated
-- first month, or a goodwill discount after a bad stay, is a legitimate slip
-- that such a constraint would refuse.


-- -----------------------------------------------------------------------------
-- WHY slip_no IS DERIVED AND NOT COUNTED
-- -----------------------------------------------------------------------------
-- The obvious format is a running counter -- PW-2026-09-0001, 0002, 0003. It is
-- also the one that cannot be produced safely. A counter needs either a row
-- lock on a counter table (a third table, for a cosmetic string) or
-- MAX(seq)+1, which two transactions read identically and both use. And it
-- cannot come from the AUTO_INCREMENT id either: MySQL forbids a generated
-- column from referring to an AUTO_INCREMENT column, so there is no way to
-- build it inside the INSERT.
--
-- So it is derived from the thing being billed, which is already unique:
--
--     PW-B-2026-09-000123    boarding request 123, stay ending Sept 2026
--     PW-D-2026-09-000045    subscription 45, billed for September 2026
--
-- Unique BY CONSTRUCTION -- uq_slip_boarding gives one slip per request, and
-- uq_slip_period one per subscription per month, so those two keys already
-- guarantee what uq_slip_no re-checks. No counter, no lock, no second
-- statement, and no race. It also means a slip re-issued after a rolled-back
-- transaction gets the SAME number rather than burning one, which is the thing
-- accountants actually complain about.
--
-- And the number reads: B or D says which system, the month says when, the
-- digits say which booking. A running counter says none of that.


-- =============================================================================
-- 5. ONE MORE notifications.type
-- =============================================================================
--
-- A slip appearing is something the owner needs to be told about, and for the
-- monthly daycare bill it is the ONLY thing that tells them: nobody is
-- standing at a counter when a @Scheduled job runs at 03:00 on the 1st.
--
-- MODIFY, NOT ADD. MySQL has no "ALTER TABLE ... ADD ENUM VALUE"; the column
-- is redeclared with the full list. Every existing value is repeated below --
-- dropping one here would silently invalidate every row holding it.
--
-- CARE_TASK_DUE is in the list because V1 put it there and V12 finally gave it
-- code. All seven now have something that raises them.

ALTER TABLE notifications
    MODIFY COLUMN type ENUM(
        'APPLICATION_SUBMITTED',
        'APPLICATION_DECIDED',
        'BOARDING_DECIDED',
        'BOARDING_UPDATE',
        'STAFF_APPROVAL',
        'CARE_TASK_DUE',
        'SLIP_ISSUED'
    ) NOT NULL;
