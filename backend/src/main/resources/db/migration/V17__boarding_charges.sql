-- =============================================================================
-- V17__boarding_charges.sql
--
-- EXPENSES DURING A STAY, NOT AT THE END OF ONE.
--
-- V15 gave a bill itemised lines (billing_slip_items), and they can only be
-- written at the moment the bill is written -- fk_slip_items_slip points at
-- billing_slips, and a slip does not exist until BoardingService.complete()
-- issues it. So the only way to charge for the worming tablet a pet had on
-- Tuesday was to remember it until Friday and type it into the check-out
-- dialog.
--
-- That is a filing system made of memory. A fortnight's boarding is fourteen
-- days of feeding, medication and the odd vet visit, entered by whoever was on
-- shift, and the person checking the animal out on Friday was not there on
-- Tuesday.
--
-- WHY NOT ISSUE THE SLIP AT CHECK-IN AND ADD LINES TO IT. Because a slip is a
-- statement of what is owed, and at check-in nothing is: period_to, units and
-- total are all NOT NULL and none of them is known until the pet is collected
-- (see BillingService.issueForBoarding -- it bills the nights ACTUALLY stayed,
-- which is the whole reason picked_up_on exists). A slip issued early would be
-- a bill for a stay that has not happened, which is exactly what the
-- BillingService class comment says there is no endpoint for.
--
-- SO THE TWO TABLES HOLD TWO DIFFERENT THINGS, AND THE DIFFERENCE IS THE
-- LIFECYCLE:
--
--   boarding_charges     ACCRUING. Staff add, correct and remove these while
--                        the animal is here. Nobody has been billed yet.
--   billing_slip_items   FROZEN. Lines on an issued bill, and a record of what
--                        the customer was actually asked to pay.
--
-- complete() copies the first into the second and the copy is the frozen one,
-- which is the same pattern the project already uses for money that must not
-- move under a customer: boarding_requests.daily_rate is copied off the
-- shelter so a price rise cannot reprice a booked stay, and billing_slips
-- .unit_rate is copied again so it cannot reprice an issued bill.
-- =============================================================================


CREATE TABLE boarding_charges (
    id          BIGINT NOT NULL AUTO_INCREMENT,

    boarding_id BIGINT NOT NULL,

    -- SAME SHAPE AS billing_slip_items, ON PURPOSE. complete() copies one into
    -- the other column for column, and a shape that drifted would mean a
    -- mapping step where a copy is enough.
    description VARCHAR(120) NOT NULL,
    quantity    DECIMAL(8,2) NOT NULL DEFAULT 1.00,
    unit_price  DECIMAL(10,2) NOT NULL,

    -- GENERATED AND STORED, as in billing_slip_items. The alternative is every
    -- reader multiplying two columns, and the one that forgets to round is the
    -- one whose total disagrees with the printed slip by a paisa.
    line_total  DECIMAL(10,2) AS (ROUND(quantity * unit_price, 2)) STORED,

    -- WHO AND WHEN, because this is the column the argument at the counter is
    -- about. An owner disputing a charge asks who added it and on what day,
    -- and "the shelter" is not an answer. ON DELETE RESTRICT would make a
    -- staff member undeletable for having fed a dog, so the reference is
    -- nullable and clears instead -- the same trade billing_slips.issued_by
    -- makes, and for the same reason: the charge is still valid without a name
    -- against it.
    added_by    BIGINT NULL,
    added_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),

    -- "This stay's charges, oldest first" is the only way anything reads this
    -- table, and it is how the check-out dialog lists them.
    KEY idx_charges_boarding (boarding_id, added_at),

    -- CASCADE, UNLIKE billing_slip_items' PARENT. A boarding request is never
    -- deleted -- fk_boarding_pet and the slips see to that -- so this is a
    -- statement about a row that cannot happen rather than a policy. It is
    -- CASCADE because if one ever did go, an orphan charge for a stay nobody
    -- can name is not something anybody could act on.
    CONSTRAINT fk_charges_boarding FOREIGN KEY (boarding_id)
        REFERENCES boarding_requests (id) ON DELETE CASCADE,

    CONSTRAINT fk_charges_user FOREIGN KEY (added_by)
        REFERENCES users (id) ON DELETE SET NULL,

    -- ck_item_quantity and ck_item_price said the same two things about
    -- billing_slip_items in V15. Said again here rather than trusted to the
    -- copy: a negative charge that reaches this table would become a negative
    -- line on a slip, and the constraint that would have caught it fires one
    -- step too late to tell anybody where it came from.
    CONSTRAINT ck_charge_quantity CHECK (quantity > 0),
    CONSTRAINT ck_charge_price    CHECK (unit_price >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- =============================================================================
-- WHAT IS DELIBERATELY NOT HERE
--
-- NO STATUS COLUMN, NO "BILLED" FLAG. Whether a charge has been billed is
-- answerable from the stay it belongs to -- a COMPLETED stay has a slip, and
-- uq_slip_boarding guarantees exactly one -- so a flag here would be a second
-- copy of that fact, free to disagree with it. The service refuses to touch
-- charges on a stay that is not ACTIVE, which is the same rule stated where it
-- can be enforced.
--
-- NO CATEGORY ENUM. 'FOOD' / 'MEDICATION' / 'GROOMING' was considered and
-- dropped: the description is what gets printed on the slip and read at the
-- counter, and a category nobody prints is a field that only exists to be
-- reported on. boarding_updates.category earns its enum because the OWNER
-- filters on it; nobody filters a fortnight's worth of feeding.
--
-- NO FOREIGN KEY TO A SLIP. The charges are copied at issue time, not linked.
-- A link would mean the frozen line and the editable one are the same row, and
-- then correcting a charge after the bill was printed would silently change
-- what the customer was asked to pay.
-- =============================================================================
