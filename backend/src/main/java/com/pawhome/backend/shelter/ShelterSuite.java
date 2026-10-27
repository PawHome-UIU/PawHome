package com.pawhome.backend.shelter;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One boarding room inside a shelter. Maps `shelter_suites` from V1__init.sql.
 *
 * shelterId is a plain Long, not a @ManyToOne Shelter -- the project's
 * convention, and here it also stops a suite listing from loading its shelter
 * once per row.
 *
 * SPECIES IS NULLABLE AND THAT IS THE DESIGN. A NULL means "takes any
 * species", which is a different statement from "we have not decided yet".
 * Anything reading this column has to handle the null rather than defaulting
 * it, or a general-purpose suite silently becomes a dog-only one.
 */
@Entity
@Table(name = "shelter_suites")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShelterSuite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shelter_id", nullable = false)
    private Long shelterId;

    /** Unique per shelter (uq_suite_per_shelter). "Suite 4", "Kitty Loft". */
    @Column(nullable = false, length = 60)
    private String name;

    /** NULL means the suite takes any species. */
    @Enumerated(EnumType.STRING)
    @Column
    private SuiteSpecies species;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    /**
     * How many animals fit in this room at once. V16.
     *
     * NOT shelters.capacity, WHICH IS A DIFFERENT NUMBER. That one is the
     * whole building's licensed headcount -- the denominator of the "28 / 50"
     * bar on the shelter record. This is the denominator of one room's bar.
     *
     * The default of 1 matches the column default and is the conservative
     * choice: it reproduces exactly the one-animal-per-room behaviour every
     * suite had before V16, so a room nobody has got round to sizing refuses a
     * second occupant rather than accepting an unbounded number.
     */
    @Column(nullable = false)
    @Builder.Default
    private int capacity = 1;
}
