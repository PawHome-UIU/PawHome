package com.pawhome.backend.boarding;

/**
 * Where a stay has got to. Maps the `boarding_requests.status` ENUM in V1.
 *
 * SIX VALUES, AND THE ORDER IS THE LIFE OF A REQUEST:
 *
 *   REQUESTED  the owner has asked. Nothing is reserved.
 *   ACCEPTED   staff said yes and assigned a suite. The dates are now HELD.
 *   ACTIVE     the pet is physically in the building.
 *   COMPLETED  the pet went home.
 *   DECLINED   staff said no. Frees the dates.
 *   CANCELLED  the owner pulled out. Also frees the dates.
 *
 * DECLINED AND CANCELLED ARE NOT THE SAME THING, even though both end the
 * request without a stay. They are kept apart for the reason REJECTED and
 * WITHDRAWN are kept apart on an adoption application: one is the shelter's
 * decision and one is the customer's, and a shelter reading its own refusal
 * rate needs to know which of the two it is looking at.
 */
public enum BoardingStatus {
    REQUESTED,
    ACCEPTED,
    ACTIVE,
    COMPLETED,
    DECLINED,
    CANCELLED;

    /**
     * The two statuses that hold a pet's dates against everybody else.
     *
     * THE OVERLAP CHECK IS DEFINED BY THIS METHOD. V1's note spells the query
     * out as `status IN ('ACCEPTED','ACTIVE')`, and writing that list out by
     * hand at each call site is how a seventh status later gets added to the
     * enum and forgotten in one of them. Named here, once.
     */
    public boolean holdsDates() {
        return this == ACCEPTED || this == ACTIVE;
    }
}
