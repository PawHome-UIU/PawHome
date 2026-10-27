package com.pawhome.backend.shelter;

/**
 * Maps the `shelters.status` ENUM in V1__init.sql. The constants are identical
 * to the SQL labels, including case -- @Enumerated(STRING) writes the name
 * straight into the column, so a rename here silently breaks every existing row.
 *
 * There is no DELETED. A shelter is never removed: pets, staff accounts and
 * completed adoptions all point at it, and an adoption that happened last year
 * still has to name the shelter that handed the animal over. INACTIVE is how a
 * shelter leaves the network.
 */
public enum ShelterStatus {

    /** Operating. Appears in the registration dropdown and the public list. */
    ACTIVE,

    /** Registered but not yet cleared by an admin. Not offered to new staff. */
    PENDING,

    /** Closed or suspended. Kept for its history; accepts nothing new. */
    INACTIVE
}
