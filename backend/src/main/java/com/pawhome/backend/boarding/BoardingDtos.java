package com.pawhome.backend.boarding;

// THE NESTED TYPE, NOT ITS OUTER CLASS. `List<@Valid BillingDtos.ChargeLine>`
// does not compile: @Valid is a type-use annotation and Java forbids annotating
// a qualified name, because BillingDtos. is a scoping construct rather than the
// type being annotated. Importing ChargeLine makes it a simple name, which is
// annotatable -- the same shape as List<@Valid InstructionRequest> below.
import com.pawhome.backend.billing.BillingDtos.ChargeLine;
import com.pawhome.backend.pet.Species;
import com.pawhome.backend.shelter.SuiteSpecies;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class BoardingDtos {

    private BoardingDtos() {}

    // ------------------------------------------------------------- reading

    /**
     * One stay, as both the owner's list and the shelter's board show it.
     *
     * @Builder for the reason PetRow and ApplicationRow have it: this is
     * thirty-odd positional arguments with runs of adjacent Strings, where a
     * swapped pair compiles cleanly and shows the wrong shelter's phone number.
     *
     * dailyRate AND estimatedCost COME FROM THE REQUEST, NOT THE SHELTER. They
     * are the quote this customer was given; V1 marks the copy as a justified
     * 3NF exception precisely so a later price rise cannot reach backwards into
     * a booking somebody already made.
     */
    @Builder
    public record BoardingRow(
            Long id,
            Long petId,
            String petName,
            String petBreed,
            Species petSpecies,
            String petCoverPhotoPath,
            Long ownerId,
            String ownerName,
            String ownerEmail,
            Long shelterId,
            String shelterName,
            String shelterAddress,
            String shelterPhone,
            /** NULL until staff accept: no room is held by a mere request. */
            Long suiteId,
            String suiteName,
            LocalDate startDate,
            LocalDate endDate,
            /**
             * When the pet was ACTUALLY collected. NULL until the stay ends.
             * Added in V13, and it is what the bill is built from -- end_date
             * is only what was booked.
             */
            LocalDate pickedUpOn,
            /**
             * Derived, never stored. end - start in days, which is the number
             * of NIGHTS -- a pet dropped off Monday and collected Tuesday
             * stays one night, and ck_boarding_dates (end > start) is what
             * guarantees this is at least 1.
             */
            int nights,
            BoardingStatus status,
            String emergencyContact,
            boolean vetCareAuthorised,
            BigDecimal dailyRate,
            BigDecimal estimatedCost,
            LocalDateTime requestedAt,
            String reviewedByName,
            LocalDateTime reviewedAt,
            String declineMessage,
            Integer quizScore,
            Integer quizMaxScore,
            Boolean quizPassed
    ) {}

    public record InstructionRow(Long id, InstructionCategory category, String body) {}

    public record UpdateRow(Long id, UpdateType updateType, String note,
                            String photoPath, String postedByName,
                            LocalDateTime postedAt) {}

    /**
     * The detail page: one stay with its two child collections.
     *
     * SEPARATE FROM BoardingRow ON PURPOSE. A list of stays that carried its
     * instructions and updates would be two extra queries per card, and the
     * board shows a dozen -- so the children are fetched once, here, for the
     * one stay somebody actually opened.
     */
    public record BoardingDetail(BoardingRow request,
                                 List<InstructionRow> instructions,
                                 List<UpdateRow> updates,
                                 /**
                                  * V17. Expenses run up so far, and what they
                                  * come to. Empty until somebody charges for
                                  * something, which is most stays.
                                  */
                                 List<ChargeRow> charges) {}

    /**
     * One expense on a stay in progress.
     *
     * SEPARATE FROM ChargeLine, WHICH IS THE REQUEST SHAPE. ChargeLine is what
     * a staff member types -- three fields, validated. This is what comes back:
     * the same three plus the id needed to remove it, the line total the
     * DATABASE computed, and who added it.
     *
     * lineTotal IS NOT quantity * unitPrice DONE IN THE BROWSER. It is
     * boarding_charges.line_total, a stored generated column. The one place
     * that multiplies is the one place that rounds, so the running total on
     * the screen and the line on the printed slip cannot differ by a paisa.
     *
     * addedByName IS WHY THE COLUMN EXISTS. The question an owner asks about a
     * charge they do not recognise is who put it there and when, and "the
     * shelter" is not an answer. Null when that staff member's account has
     * since been deleted -- fk_charges_user is ON DELETE SET NULL, because
     * nobody should be undeletable for having fed a dog.
     */
    public record ChargeRow(Long id, String description, BigDecimal quantity,
                            BigDecimal unitPrice, BigDecimal lineTotal,
                            String addedByName, LocalDateTime addedAt) {}

    /**
     * A room, for the accept dialog.
     *
     * `occupied` is not a column. It is computed from the stays currently
     * holding the requested dates, because a suite is only "taken" relative to
     * a period -- the same room is free next week.
     *
     * V16 MAKES IT A FRACTION AND KEEPS THE FLAG. `occupied` now means FULL --
     * no places left over these dates -- which is what the dropdown greys out
     * on, and it is still a single boolean because that is the only question
     * the control can act on. placesUsed / capacity is what the option LABEL
     * says, and it exists because "Kennel 1 (taken)" and "Kennel 1 (2 of 2)"
     * are the same refusal but only the second one tells a staff member
     * whether tomorrow will be different.
     */
    public record SuiteRow(Long id, String name, SuiteSpecies species,
                           boolean active, boolean occupied,
                           int placesUsed, int capacity,
                           /**
                            * V17. The room this stay is in already.
                            *
                            * NEEDED BECAUSE THE DIALOG IS NOW ALSO A MOVE
                            * DIALOG. When a stay already holds a suite, that
                            * suite's placesUsed EXCLUDES this pet -- otherwise
                            * a single-place kennel would show as full because
                            * of the animal you are trying to move out of it,
                            * and the one room a move must always be able to
                            * offer is the one it is leaving.
                            */
                           boolean current) {}

    public record BoardingCounts(long requested, long active,
                                 long departingToday, long completed) {}

    // ------------------------------------------------------------- writing

    public record InstructionRequest(
            @NotNull(message = "Choose a category.") InstructionCategory category,
            @NotBlank @Size(max = 2000) String body
    ) {}

    /**
     * Asking for a stay.
     *
     * NO status, NO daily_rate, NO estimated_cost, NO owner. The status is
     * REQUESTED because nothing else makes sense; the owner is the caller; and
     * the two money columns are read from the shelter server-side, because a
     * price the browser sends is a customer setting their own bill.
     */
    public record RequestBoarding(
            @NotNull Long petId,
            @NotNull Long shelterId,
            @NotNull(message = "Choose a drop-off date.") LocalDate startDate,
            @NotNull(message = "Choose a collection date.") LocalDate endDate,
            /** Optional: V6 notes a request may predate the boarding quiz. */
            Long quizAttemptId,
            @NotBlank(message = "Give us someone to call in an emergency.")
            @Size(max = 160) String emergencyContact,
            boolean vetCareAuthorised,
            @Size(max = 12, message = "That is more care notes than anyone will read.")
            List<@Valid InstructionRequest> instructions
    ) {}

    /**
     * Checking a pet out, and billing for it.
     *
     * THE DATES ARE NOT IN HERE. Collection is today by definition -- that is
     * what "the owner is at the counter" means -- so complete() stamps
     * picked_up_on itself. A date the browser could send would be a staff
     * member able to bill for nights that have not happened.
     *
     * EARLY PICKUP NEEDS NO FLAG EITHER. A stay completed before its end_date
     * IS an early pickup, and the slip already bills start_date ->
     * picked_up_on, so three nights of a booked five are billed as three.
     *
     * extras IS OPTIONAL AND USUALLY EMPTY: most stays are just the nights.
     */
    public record CompleteBoarding(
            @Size(max = 20, message = "That is more charges than a bill should carry.")
            List<@Valid ChargeLine> extras
    ) {}

    /** Accepting. The suite is optional only because the column is. */
    public record AcceptRequest(Long suiteId) {}

    /**
     * Moving a pet to a different room, mid-stay.
     *
     * SEPARATE FROM AcceptRequest EVEN THOUGH IT IS THE SAME ONE FIELD,
     * because the field is not optional here. Accepting without a suite is
     * legal -- the room can be decided later -- but "move this pet" with no
     * room named is not a request, it is a mistake. @NotNull says so on the
     * field rather than leaving the service to guess which was meant.
     */
    public record MoveSuite(
            @NotNull(message = "Choose a room to move them to.") Long suiteId
    ) {}

    /**
     * Declining.
     *
     * The message is required here and not by the database -- boarding has no
     * equivalent of ck_apps_decline. That asymmetry is the schema's, not a
     * mistake to copy: the service holds the line because a refusal with no
     * reason is what makes a customer phone up.
     */
    public record DeclineBoarding(
            @NotBlank @Size(min = 10, max = 2000,
                    message = "Please write at least a sentence.") String message
    ) {}
}
