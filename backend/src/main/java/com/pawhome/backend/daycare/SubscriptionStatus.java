package com.pawhome.backend.daycare;

/**
 * Where a daycare subscription has got to. Maps the
 * `daycare_subscriptions.status` ENUM in V13.
 *
 * CANCELLED AND EXPIRED ARE NOT THE SAME THING, for the reason DECLINED and
 * CANCELLED are kept apart on a boarding request: one is a decision somebody
 * made and one is a date passing. A shelter looking at why its morning slot
 * emptied needs to know which of the two it is looking at -- customers leaving
 * is a problem, agreed terms ending is not.
 *
 * ONLY ACTIVE COUNTS AGAINST CAPACITY, and only ACTIVE is billed. Both of
 * those read this one value, which is why idx_sub_capacity ends with it.
 */
public enum SubscriptionStatus {

    /** Running. Holds a place in the slot, and is billed on the 1st. */
    ACTIVE,

    /** The owner stopped it. end_date is the day they said so. */
    CANCELLED,

    /** It reached an end_date agreed up front. Nobody had to do anything. */
    EXPIRED;

    /**
     * Does a row in this state hold a place in its slot?
     *
     * NAMED HERE, ONCE, for the reason BoardingStatus.holdsDates() is: writing
     * `status = 'ACTIVE'` at each call site is how a fourth value later gets
     * added to the enum and forgotten in one of them.
     */
    public boolean holdsPlace() {
        return this == ACTIVE;
    }
}
