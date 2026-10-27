package com.pawhome.backend.pet;

/** Maps `pets.age_unit`. Shelters rarely know a rescue animal's birth date, so the schema stores a number and a unit rather than a date -- "about 8 weeks" is the honest record, and a fabricated date of birth would read as fact forever.
 *
 * The constants are identical to the SQL labels: @Enumerated(STRING) writes
 * the name straight into the column, so a rename here breaks existing rows.
 */
public enum AgeUnit { WEEKS,MONTHS,YEARS }
