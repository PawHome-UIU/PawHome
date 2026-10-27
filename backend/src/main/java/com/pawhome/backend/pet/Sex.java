package com.pawhome.backend.pet;

/** Maps `pets.sex`.
 *
 * The constants are identical to the SQL labels: @Enumerated(STRING) writes
 * the name straight into the column, so a rename here breaks existing rows.
 */
public enum Sex { MALE,FEMALE }
