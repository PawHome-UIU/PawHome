package com.pawhome.backend.application;

import com.pawhome.backend.pet.PetStatus;
import jakarta.validation.constraints.*;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public final class ApplicationDtos {

    private ApplicationDtos() {}

    // ------------------------------------------------------------- reading

    /**
     * One row of the review queue, and of the adopter's own list.
     *
     * @Builder because this is the same trap PetRow was: twenty-odd positional
     * arguments where several neighbours are Strings, so a swap compiles.
     *
     * THE APPLICANT DETAILS ARE THE APPLICATION'S OWN COPY, not the user's
     * current ones -- see AdoptionApplication. What is shown to a reviewer has
     * to be what was said when it was submitted.
     */
    @Builder
    public record ApplicationRow(
            Long id,
            Long petId,
            String petName,
            String petBreed,
            String petCoverPhotoPath,
            PetStatus petStatus,
            Long shelterId,
            String shelterName,
            Long applicantId,
            String applicantName,
            String applicantEmail,
            String applicantPhone,
            String applicantAddress,
            String applicantCity,
            String homeType,
            String timeAtHome,
            String petExperience,
            String householdNote,
            short quizScore,
            /**
             * What the attempt could have scored, so the mark can be shown as
             * a fraction. Read from the attempt rather than stored on the
             * application: the score is the judgement, the maximum is context.
             */
            Integer quizMaxScore,
            Boolean quizPassed,
            ApplicationStatus status,
            LocalDateTime submittedAt,
            String reviewedByName,
            LocalDateTime reviewedAt,
            DeclineReason declineReasonCode,
            String declineMessage,
            /**
             * The adoption record, and null on anything but an APPROVED row.
             *
             * Joined in rather than fetched separately because
             * uq_adoption_application makes the join incapable of duplicating a
             * row -- at most one adoption per application, enforced by the
             * database -- so the cost is two columns, not a second endpoint the
             * adopter's page would have to call and reconcile.
             */
            LocalDate adoptedOn,
            BigDecimal feePaid
    ) {}

    public record ApplicationCounts(long total, long pending, long approved,
                                    long rejected, long withdrawn) {}

    // ------------------------------------------------------------- writing

    /**
     * Applying.
     *
     * NO status, NO quizScore, NO applicantId. The status is PENDING because
     * nothing else makes sense for a new one; the score is read from the
     * attempt server-side, because a score the browser sends is an applicant
     * marking their own eligibility; and the applicant is the caller.
     */
    public record ApplyRequest(
            @NotNull Long petId,
            @NotNull(message = "Take the eligibility quiz first.")
            Long eligibilityAttemptId,
            @NotBlank @Size(max = 120) String applicantName,
            @NotBlank @Email @Size(max = 160) String applicantEmail,
            @NotBlank @Size(max = 30) String applicantPhone,
            @NotBlank @Size(max = 200) String applicantAddress,
            @NotBlank @Size(max = 80) String applicantCity,
            @NotBlank @Size(max = 60) String homeType,
            @NotBlank @Size(max = 60) String timeAtHome,
            @NotBlank @Size(max = 200) String petExperience,
            @Size(max = 200) String householdNote
    ) {}

    /**
     * Approving.
     *
     * Both fields are optional and default sensibly -- today, and the pet's
     * listed fee. A reviewer approving twenty applications should not have to
     * retype today's date twenty times, and a fee that differs from the listing
     * is the exception.
     */
    public record ApproveRequest(
            LocalDate adoptedOn,
            @PositiveOrZero(message = "A fee cannot be negative.")
            @Digits(integer = 8, fraction = 2) BigDecimal feePaid
    ) {}

    /**
     * Declining.
     *
     * BOTH ARE REQUIRED, and not by choice: ck_apps_decline refuses a REJECTED
     * row missing either. The message minimum is this service's own -- the
     * database would accept "no", and a person reading it would not.
     */
    public record DeclineRequest(
            @NotNull(message = "Choose a reason.") DeclineReason reasonCode,
            @NotBlank @Size(min = 10, max = 2000,
                    message = "Please write at least a sentence.") String message
    ) {}
}
