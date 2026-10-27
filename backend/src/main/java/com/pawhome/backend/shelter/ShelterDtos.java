package com.pawhome.backend.shelter;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public final class ShelterDtos {

    private ShelterDtos() {}

    /**
     * One row of the admin Shelters table.
     *
     * petCount and staffCount are COUNTED, not stored. They exist on the DTO
     * and nowhere else, which is the whole point -- a stored occupancy figure
     * is wrong the first time a pet is adopted by any code path that forgets
     * to decrement it.
     */
    public record ShelterRow(
            Long id,
            String name,
            String email,
            String phone,
            String addressLine,
            String city,
            String state,
            String zip,
            String location,          // "Austin, TX" -- what the table shows
            int capacity,
            long petCount,
            long staffCount,
            long suiteCount,
            /**
             * V16. How many animals this shelter's ACTIVE rooms hold between
             * them -- the sum of shelter_suites.capacity.
             *
             * NOT suiteCount, AND NOT capacity. suiteCount is how many rooms
             * exist; `capacity` above is the building's licensed headcount,
             * which an admin types. This is what the rooms staff have actually
             * built can physically house, and it is the number
             * daycareSlotCapacity has to be sane against: a band that sells
             * twenty places at a shelter whose rooms hold ten has oversold,
             * and nothing used to notice.
             */
            int suitePlaces,
            ShelterStatus status,
            String description,
            BigDecimal boardingRateDaily,
            /**
             * V13. Zero means this shelter does not run daycare -- the column
             * has a default for every shelter that existed before V13, so
             * "has a price" is what opting in actually looks like.
             */
            BigDecimal daycareMonthlyPrice,
            /**
             * V13. How many pets may hold the SAME daily band at once. NOT the
             * `capacity` above, which counts kennel places for residents -- a
             * pet in the midday band goes home at 15:00 and occupies none.
             */
            int daycareSlotCapacity,
            String logoPath,
            LocalDateTime createdAt,
            /**
             * ANIMALS PHYSICALLY IN THE BUILDING RIGHT NOW: residents awaiting
             * adoption, boarders who have checked in, and daycare pets present
             * today.
             *
             * NOT petCount, WHICH MEANS SOMETHING ELSE. petCount is the
             * shelter's BOOKS -- every animal it is responsible for that has
             * not been adopted out. Both screens used to render petCount under
             * the label "Pets in Care", which is how accepting two boarding
             * requests appeared to change nothing: a boarded pet belongs to a
             * household, has no shelter_id, and was never on those books.
             */
            long onSiteCount
    ) {}

    /**
     * What the registration dropdown needs, and nothing else.
     *
     * This one is reachable WITHOUT a token, because a staff member has to
     * choose their shelter before they have an account. So it deliberately
     * carries no email, no phone, no capacity and no counts: an anonymous
     * caller gets a name and an id, which is all the form needs.
     */
    /**
     * The picker: staff sign-up, and the boarding request form.
     *
     * boardingRateDaily is here so the boarding form can show a nightly price
     * and a running estimate before the customer commits to a shelter. It is
     * public information -- this endpoint is permitted to guests by name in
     * SecurityConfig -- and a price list is exactly the sort of thing a
     * shelter wants read before somebody books.
     */
    public record ShelterOption(Long id, String name, String city, String state,
                                BigDecimal boardingRateDaily) {}

    public record CreateShelterRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Email @Size(max = 160) String email,
            @NotBlank @Size(max = 30) String phone,
            @NotBlank @Size(max = 200) String addressLine,
            @NotBlank @Size(max = 80) String city,
            @NotBlank @Size(max = 80) String state,
            @NotBlank @Size(max = 20) String zip,

            // ck_shelters_capacity says > 0. Saying so here too turns a
            // constraint violation into a sentence on the right form field.
            @NotNull @Min(value = 1, message = "Capacity must be at least 1.")
            @Max(value = 100000, message = "That capacity looks like a typo.")
            Integer capacity,

            ShelterStatus status,
            @Size(max = 5000) String description,

            @DecimalMin(value = "0.00", message = "A daily rate cannot be negative.")
            @Digits(integer = 8, fraction = 2)
            BigDecimal boardingRateDaily,

            // V13. Both optional: a shelter that does not run daycare simply
            // leaves the price at zero, which is what DaycareService.offers()
            // filters on.
            @DecimalMin(value = "0.00", message = "A monthly price cannot be negative.")
            @Digits(integer = 8, fraction = 2)
            BigDecimal daycareMonthlyPrice,

            @Min(value = 0, message = "A slot capacity cannot be negative.")
            @Max(value = 1000, message = "That slot capacity looks like a typo.")
            Integer daycareSlotCapacity,

            @Size(max = 255) String logoPath
    ) {}

    /**
     * Update carries the same fields, minus status.
     *
     * Status changes go through their own endpoint on purpose. Folding them in
     * here means an admin editing a phone number can deactivate a shelter by
     * sending a stale form, and neither of them would notice.
     */
    public record UpdateShelterRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Email @Size(max = 160) String email,
            @NotBlank @Size(max = 30) String phone,
            @NotBlank @Size(max = 200) String addressLine,
            @NotBlank @Size(max = 80) String city,
            @NotBlank @Size(max = 80) String state,
            @NotBlank @Size(max = 20) String zip,
            @NotNull @Min(1) @Max(100000) Integer capacity,
            @Size(max = 5000) String description,
            @DecimalMin("0.00") @Digits(integer = 8, fraction = 2)
            BigDecimal boardingRateDaily,
            @DecimalMin("0.00") @Digits(integer = 8, fraction = 2)
            BigDecimal daycareMonthlyPrice,
            @Min(0) @Max(1000) Integer daycareSlotCapacity,
            @Size(max = 255) String logoPath
    ) {}

    public record StatusRequest(@NotNull ShelterStatus status) {}

    // ------------------------------------------------------------- suites

    public record SuiteRow(
            Long id,
            Long shelterId,
            String name,
            SuiteSpecies species,     // null = takes any species
            boolean active,
            /** V16. How many animals fit at once. NOT the shelter's capacity. */
            int capacity
    ) {}

    /**
     * A room, and whoever is in it right now.
     *
     * SEPARATE FROM SuiteRow, deliberately. SuiteRow is what create, rename and
     * retire hand back -- a description of the room itself. This is a
     * description of the BUILDING at a moment, and computing an occupant on
     * every rename would be work for an answer nobody asked for.
     *
     * THREE STATES, NOT TWO. "Free" and "occupied" leaves nowhere to put a room
     * that is booked for tonight but empty at four in the afternoon, and a
     * shelter that treats that room as free will double-book it.
     *
     * V16 ADDS A NUMERATOR AND A DENOMINATOR, because `state` alone stopped
     * being the whole answer the moment a room could hold twenty cats. "Cat
     * Loft A -- occupied" is true with one cat in it and true with twenty, and
     * those are not the same room to anybody deciding where to put the
     * twenty-first. petName/ownerName still name ONE occupant, the earliest
     * arrival, so the line reads like a sentence; `occupied` is how many there
     * actually are, and the On Site Now panel is where the full list lives.
     */
    public record RoomRow(
            Long id,
            String name,
            SuiteSpecies species,
            boolean active,
            /** OCCUPIED, RESERVED, DAYCARE or FREE. Derived, never stored. */
            String state,
            Long petId,
            String petName,
            String ownerName,
            /** Boarding: the booked dates. Null for a daycare visit. */
            LocalDate from,
            LocalDate to,
            /** Daycare: which band. Null for a boarding stay. */
            String slotLabel,
            /** Nights so far for a boarder; null otherwise. */
            Integer nightsSoFar,
            /**
             * V16. Animals in this room right now: overnight stays holding it
             * today plus daycare pets checked in and not yet gone home.
             * COUNTED on every read, never stored -- a stored occupancy figure
             * is wrong the first time anybody checks out by a path that
             * forgets to decrement it.
             */
            int occupied,
            /** V16. shelter_suites.capacity -- the room's places. */
            int capacity
    ) {}

    /**
     * One animal on the premises, and how long it has been here.
     *
     * THREE KINDS IN ONE LIST, because the question a staff member asks is
     * "who is here", not "who is here of each kind". The `kind` column is what
     * tells them apart, and it comes from which arm of a UNION the row is
     * from -- there is no column anywhere that stores it.
     */
    public record OnSiteRow(
            /** RESIDENT, BOARDING or DAYCARE. */
            String kind,
            Long petId,
            String petName,
            String species,
            /** The owner, for a boarder or daycare pet. Null for a resident. */
            String ownerName,
            String roomName,
            /** Arrival: intake date, stay start, or today. */
            LocalDate since,
            /** Whole days on the premises. Same unit for all three kinds. */
            long daysHere
    ) {}

    public record SuiteRequest(
            @NotBlank @Size(max = 60) String name,
            /** Null is meaningful: the suite takes any species. */
            SuiteSpecies species,
            Boolean active,
            /**
             * V16. Optional, and null means "leave it alone" rather than
             * "one": a rename posted from an older form must not silently
             * shrink a twenty-place cat loft back to a single kennel. The
             * service treats null as "keep the current value" on update and as
             * the column default of 1 on create.
             *
             * The bounds are ck_suites_capacity said twice, so a typo comes
             * back as a sentence on the field rather than a 500 from InnoDB.
             */
            @Min(value = 1, message = "A room must have at least one place.")
            @Max(value = 500, message = "That capacity looks like a typo.")
            Integer capacity
    ) {}
}
