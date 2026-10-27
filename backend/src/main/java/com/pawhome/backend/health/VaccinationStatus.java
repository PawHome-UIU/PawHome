package com.pawhome.backend.health;

/**
 * Maps `vaccinations.status`.
 *
 * TWO VALUES, AND NEITHER IS "OVERDUE". V1 is explicit that "Overdue
 * Vaccinations (3)" is a query on next_due_on, not a stored flag -- a third
 * status would be a copy of today's date frozen into a row, wrong from the
 * moment the clock passes it and right again only if something remembers to
 * sweep the table.
 */
public enum VaccinationStatus {
    /** Given. administered_on is when. */
    COMPLETED,
    /** Booked but not yet given. */
    SCHEDULED
}
