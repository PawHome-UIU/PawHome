package com.pawhome.backend.shelter;

/**
 * Maps the `shelter_suites.species` ENUM in V1__init.sql.
 *
 * This is the SUITE's restriction, not the pet's. It is a deliberately shorter
 * list than the pets.species enum: a suite is a room, and rooms are built for
 * dogs, for cats, or for whatever else fits. A NULL column -- not a constant
 * here -- means the suite takes any species.
 */
public enum SuiteSpecies {
    DOG,
    CAT,
    OTHER
}
