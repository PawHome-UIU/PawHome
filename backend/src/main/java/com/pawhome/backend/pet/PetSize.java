package com.pawhome.backend.pet;

/** Maps `pets.size`. Part of idx_pets_browse, which is what makes the guest filter fast.
 *
 * The constants are identical to the SQL labels: @Enumerated(STRING) writes
 * the name straight into the column, so a rename here breaks existing rows.
 */
public enum PetSize { SMALL,MEDIUM,LARGE,GIANT }
