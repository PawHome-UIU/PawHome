package com.pawhome.backend.boarding;

/**
 * Maps the `boarding_instructions.category` ENUM in V1__init.sql.
 *
 * Rows rather than one blob of text, as V1's comment argues: the owner's care
 * notes are grouped on the detail page, and a new grouping should be a new
 * enum value rather than a new parser.
 */
public enum InstructionCategory {
    DIETARY,
    MEDICATION,
    COMFORT_PLAY,
    EXERCISE,
    OTHER
}
