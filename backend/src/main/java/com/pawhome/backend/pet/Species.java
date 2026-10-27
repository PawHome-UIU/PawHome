package com.pawhome.backend.pet;

/** Maps `pets.species`.
 *
 * The constants are identical to the SQL labels: @Enumerated(STRING) writes
 * the name straight into the column, so a rename here breaks existing rows.
 */
public enum Species { DOG,CAT,OTHER }
