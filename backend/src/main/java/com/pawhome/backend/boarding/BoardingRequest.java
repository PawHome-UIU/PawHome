package com.pawhome.backend.boarding;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A request to board an owned pet. Maps `boarding_requests` from V1__init.sql.
 *
 * THE DATES ARE THE WHOLE PROBLEM. ck_boarding_dates enforces end > start and
 * nothing more, because the rule that actually matters -- one animal cannot be
 * in two places at once -- is not expressible as a CHECK. PostgreSQL would say
 * it declaratively with an EXCLUDE constraint; MySQL has no equivalent, so V1
 * moves the guarantee into a transaction and BoardingService implements it.
 * Read the note at the bottom of the CREATE TABLE before touching accept().
 *
 * daily_rate AND estimated_cost ARE COPIES, and V1 marks them as a justified
 * 3NF exception. shelters.boarding_rate_daily is what the shelter charges
 * TODAY; these two are what this customer was quoted. A shelter that raises
 * its rate must not thereby raise the price of a stay somebody already booked,
 * which is exactly what would happen if the page joined the live rate instead.
 *
 * quiz_attempt_type IS NOT MAPPED. It is a constant column, pinned by
 * ck_boarding_quiz_type, that exists only so fk_boarding_attempt can be
 * composite -- (quiz_attempt_id, quiz_attempt_type) against
 * quiz_attempts (id, quiz_type) -- which is what makes it impossible to attach
 * an eligibility or personality attempt to a boarding request. Its default is
 * 'BOARDING' and nothing may ever set it to anything else, so it is left off
 * the entity rather than given a field somebody could assign.
 */
@Entity
@Table(name = "boarding_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BoardingRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pet_id", nullable = false)
    private Long petId;

    /**
     * Must equal pets.owner_id at request time, checked in the service because
     * a CHECK constraint cannot read another table (V1 says so). Stored rather
     * than derived so that a pet later rehomed does not rewrite the history of
     * who asked for this stay.
     */
    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "shelter_id", nullable = false)
    private Long shelterId;

    /** NULL until staff accept and assign a room. */
    @Column(name = "suite_id")
    private Long suiteId;

    /** NULL is allowed: V6 notes a request may predate the boarding quiz. */
    @Column(name = "quiz_attempt_id")
    private Long quizAttemptId;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    /**
     * The day the pet was ACTUALLY collected. Added in V13; set by complete().
     *
     * WHY end_date IS NOT ENOUGH. end_date is what was BOOKED. Until V13 that
     * was the only date anywhere, so a bill built from it charged five nights
     * to somebody who collected their dog on the third morning -- and they are
     * standing at the counter holding the proof. The slip is built from this
     * column instead.
     *
     * NULLABLE, because it is only true of a stay that has ended. A REQUESTED
     * or ACTIVE row has no collection date and must not pretend to.
     * ck_boarding_picked_up allows NULL and otherwise requires it to be on or
     * after start_date -- with deliberately NO upper bound, because a pet
     * collected LATE is a real thing and billing for it is the other half of
     * the same honesty.
     */
    @Column(name = "picked_up_on")
    private LocalDate pickedUpOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private BoardingStatus status = BoardingStatus.REQUESTED;

    @Column(name = "emergency_contact", nullable = false, length = 160)
    private String emergencyContact;

    /** Its own column, not a quiz answer: staff need it at a glance. */
    @Column(name = "vet_care_authorised", nullable = false)
    @Builder.Default
    private boolean vetCareAuthorised = false;

    @Column(name = "daily_rate", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal dailyRate = BigDecimal.ZERO;

    @Column(name = "estimated_cost", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal estimatedCost = BigDecimal.ZERO;

    /** Database default, so never written from here. */
    @Column(name = "requested_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime requestedAt;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "decline_message", columnDefinition = "TEXT")
    private String declineMessage;
}
