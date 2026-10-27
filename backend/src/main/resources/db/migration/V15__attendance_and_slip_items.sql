-- =============================================================================
-- V15__attendance_and_slip_items.sql
--
-- TWO THINGS THE SYSTEM COULD NOT ANSWER.
--
-- 1. "WHICH ANIMALS ARE IN THE BUILDING RIGHT NOW, AND IN WHICH ROOM?"
--
--    Nothing recorded presence. boarding_requests.status told you a stay was
--    ACTIVE, which is close, and daycare told you somebody had a standing
--    arrangement, which is not close at all -- a subscription says a pet comes
--    every weekday, not that it is here today. So the staff "Pets in Care"
--    tile counted pets on the shelter's BOOKS and was read by everyone as
--    pets in the shelter's CARE. Those are different sets, and the label was
--    the part that was wrong.
--
--    daycare_attendance is the missing fact: one row per pet per day, with the
--    room it was put in and the times it arrived and left.
--
-- 2. "THE OWNER IS COLLECTING EARLY AND OWES FOR MEDICATION."
--
--    A slip could bill nights at a nightly rate and nothing else. Real
--    collections are not like that: a dog on antibiotics, an extra bag of the
--    food it actually eats, a bath before it goes home. V13 deliberately left
--    billing_slips.total free-form -- with no CHECK tying it to
--    units * unit_rate -- precisely so this could arrive later. This is later.
--
-- 29 tables -> 31.
-- =============================================================================


-- =============================================================================
-- 1. THE TARGET FOR A COMPOSITE FOREIGN KEY
-- =============================================================================
--
-- The attendance table below needs to carry the band a visit belongs to, so a
-- room can hold a morning pet and an afternoon pet on the same day without the
-- two colliding. That band must be the SAME band the subscription is for --
-- and "must be the same as its parent's" is exactly what V6 solved for quiz
-- types, with a two-column key.
--
-- On its own this is redundant: id is already unique. It exists to give the
-- child a pair to point at.

ALTER TABLE daycare_subscriptions
    ADD UNIQUE KEY uq_sub_id_slot (id, slot);


-- =============================================================================
-- 2. daycare_attendance -- who was here, when, and in which room
-- =============================================================================

CREATE TABLE daycare_attendance (
    id              BIGINT NOT NULL AUTO_INCREMENT,

    subscription_id BIGINT NOT NULL,

    -- A COPY OF daycare_subscriptions.slot, and the composite foreign key below
    -- is what stops it ever disagreeing. It is here because uq_suite_band needs
    -- it: without the band in the key, one room could take one animal per DAY,
    -- which throws away the whole point of having three bands.
    slot            ENUM('MORNING','MIDDAY','AFTERNOON') NOT NULL,

    visit_date      DATE NOT NULL,

    -- ASSIGNED AT CHECK-IN, NOT AT SIGNUP. A subscription is an agreement about
    -- a time of day; the room depends on who else is in the building that
    -- morning, which nobody knows in advance.
    --
    -- NOT NULL: a pet that is here is somewhere. "Checked in, room unknown" is
    -- not a state a shelter should be able to record.
    suite_id        BIGINT NOT NULL,

    checked_in_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    checked_in_by   BIGINT   NOT NULL,

    -- NULL means STILL HERE. That is the whole "who is on site now" query:
    -- visit_date = today AND checked_out_at IS NULL.
    checked_out_at  DATETIME NULL,
    checked_out_by  BIGINT   NULL,

    PRIMARY KEY (id),

    -- ONE VISIT PER SUBSCRIPTION PER DAY. Makes check-in idempotent against a
    -- double-clicked button, and makes "did this pet come today" a lookup
    -- rather than a count.
    UNIQUE KEY uq_visit_per_day (subscription_id, visit_date),

    -- ONE ANIMAL PER ROOM PER BAND. The rule the shelter actually cares about,
    -- and the reason `slot` is duplicated onto this table. Note what it
    -- deliberately ALLOWS: the same room taking a 09:00 pet and a 15:00 pet on
    -- the same day, which is how a room earns its keep.
    UNIQUE KEY uq_suite_band (suite_id, visit_date, slot),

    -- The board: "show me today, room by room."
    KEY idx_attendance_board (visit_date, suite_id),
    -- "Who is on site right now" -- the leftmost two columns of the predicate.
    KEY idx_attendance_present (visit_date, checked_out_at),

    CONSTRAINT fk_attendance_sub FOREIGN KEY (subscription_id, slot)
        REFERENCES daycare_subscriptions (id, slot) ON DELETE RESTRICT,
    CONSTRAINT fk_attendance_suite FOREIGN KEY (suite_id)
        REFERENCES shelter_suites (id)  ON DELETE RESTRICT,
    CONSTRAINT fk_attendance_in_by FOREIGN KEY (checked_in_by)
        REFERENCES users (id)           ON DELETE RESTRICT,
    CONSTRAINT fk_attendance_out_by FOREIGN KEY (checked_out_by)
        REFERENCES users (id)           ON DELETE RESTRICT,

    -- Left before it arrived is not an early collection, it is a bad row.
    CONSTRAINT ck_attendance_times CHECK (
        checked_out_at IS NULL OR checked_out_at >= checked_in_at),

    -- BOTH HALVES OF A CHECK-OUT, OR NEITHER. A departure time with nobody's
    -- name against it is the row you cannot follow up on, and a name with no
    -- time says the pet left at no particular moment.
    CONSTRAINT ck_attendance_out_pair CHECK (
        (checked_out_at IS     NULL AND checked_out_by IS     NULL) OR
        (checked_out_at IS NOT NULL AND checked_out_by IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- -----------------------------------------------------------------------------
-- TWO RULES THAT ARE NOT CONSTRAINTS HERE, AND CANNOT BE
-- -----------------------------------------------------------------------------
-- A. THE ROOM MUST BELONG TO THE SAME SHELTER AS THE SUBSCRIPTION.
--    Both facts live in other tables, and a MySQL CHECK cannot read one. The
--    service compares them, exactly as boarding_requests.owner_id is compared
--    against pets.owner_id.
--
-- B. A ROOM HELD BY AN OVERNIGHT BOARDER IS NOT FREE FOR DAYCARE.
--    uq_suite_band stops two DAYCARE pets sharing a room in one band. It knows
--    nothing about boarding_requests, where a stay holds a room across a RANGE
--    of dates. "This room is not booked by anyone on this date" is a question
--    about rows in another table over an interval -- the same shape as the
--    boarding overlap rule V1 already handed to a transaction, for the same
--    reason: MySQL has no EXCLUDE constraint.
--
--    So DaycareService takes a pessimistic lock on the overlapping boarding
--    rows before it writes. Read
--    BoardingRequestRepository.lockOverlappingForSuite first.


-- =============================================================================
-- 3. billing_slip_items -- what a bill says besides the nights
-- =============================================================================

CREATE TABLE billing_slip_items (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    slip_id     BIGINT NOT NULL,

    -- "Antibiotics, twice daily", "Extra food (own brand)", "Bath before
    -- collection". Written by the staff member at the counter, read by the
    -- customer, so it is free text and not an enum: the day somebody needs
    -- "replacement collar" and the enum has no value for it, the charge goes
    -- in as OTHER and the line stops explaining itself.
    description VARCHAR(120) NOT NULL,

    -- DECIMAL, not INT: half a bag of food and 2.5 hours of grooming are real.
    quantity    DECIMAL(8,2)  NOT NULL DEFAULT 1.00,
    unit_price  DECIMAL(10,2) NOT NULL,

    -- GENERATED, NOT STORED BY HAND. Unlike billing_slips.total -- which is
    -- deliberately free-form so a goodwill discount is expressible -- a LINE
    -- has no reason to say anything other than quantity x price. Computing it
    -- in the database means the arithmetic cannot drift from the numbers
    -- beside it, and no caller can post a line that does not add up.
    line_total  DECIMAL(10,2) AS (ROUND(quantity * unit_price, 2)) STORED,

    -- The order the staff member typed them in. Without it the bill reorders
    -- itself between two readings of the same row set.
    sort_order  SMALLINT NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    KEY idx_slip_items (slip_id, sort_order),

    -- CASCADE, unlike most foreign keys here, because a line has no meaning
    -- without its slip -- the same reason boarding_updates cascades from its
    -- stay. Slips are never deleted in practice; this is about what the row
    -- MEANS, not about an expected operation.
    CONSTRAINT fk_slip_items_slip FOREIGN KEY (slip_id)
        REFERENCES billing_slips (id) ON DELETE CASCADE,

    CONSTRAINT ck_item_quantity CHECK (quantity > 0),
    CONSTRAINT ck_item_price    CHECK (unit_price >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- -----------------------------------------------------------------------------
-- HOW A SLIP ADDS UP NOW
-- -----------------------------------------------------------------------------
--   units x unit_rate            the base charge: nights boarded, or one month
--   + SUM(billing_slip_items)    everything else
--   = billing_slips.total        stored, because a slip is a document
--
-- THE BASE LINE IS NOT AN ITEM, and that is a deliberate choice rather than an
-- oversight. Making everything a line would be the textbook invoice shape, and
-- it would also mean rewriting the issuing path, the printable page and the
-- three unique keys that already make issuing idempotent -- for a slip that
-- reads identically either way. The base charge is the one line every slip has
-- by definition; items are the ones only some slips have.
--
-- total STAYS STORED AND STAYS UNCHECKED. V13's reason was a prorated month or
-- a goodwill discount. This is the second reason: recomputing a slip from
-- today's items would let an edit to a line silently rewrite a bill the
-- customer was handed last week.
