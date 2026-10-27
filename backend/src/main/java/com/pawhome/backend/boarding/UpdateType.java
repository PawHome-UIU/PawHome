package com.pawhome.backend.boarding;

/**
 * Maps the `boarding_updates.update_type` ENUM in V1__init.sql.
 *
 * ARRIVAL_CHECK_IN and DEPARTURE are written by the service when the stay
 * changes state, not chosen by a staff member from a dropdown: they are the
 * record OF the check-in, so posting one by hand without the status moving
 * would put an arrival note on a stay that has not started.
 */
public enum UpdateType {
    ARRIVAL_CHECK_IN,
    FEEDING,
    PLAYTIME,
    MEDICATION,
    HEALTH,
    DEPARTURE,
    /**
     * The pet was moved between rooms. V18.
     *
     * A THIRD AUTOMATIC TYPE. Like ARRIVAL_CHECK_IN and DEPARTURE, this is the
     * RECORD OF an event rather than a comment about one, so
     * BoardingService.postUpdate refuses it and only moveSuite() writes it. A
     * timeline entry saying the pet moved therefore always means the pet moved.
     *
     * LAST IN THE LIST, and that is not cosmetic. MySQL stores an ENUM as the
     * INDEX of the value, so inserting this in the middle would renumber every
     * value after it and silently change what every existing row means. See
     * V18.
     */
    ROOM_CHANGE
}
