package com.pawhome.backend.daycare;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A standing arrangement to leave one pet in one daily slot. Maps
 * `daycare_subscriptions` from V13__daycare_and_billing_slips.sql.
 *
 * monthly_price IS A COPY, and V13 marks it as a justified 3NF exception --
 * the same one boarding_requests.daily_rate already makes.
 * shelters.daycare_monthly_price is what the shelter charges TODAY; this is
 * what THIS customer agreed to. The monthly billing job reads this column and
 * not the shelter's, so an admin raising the price next spring cannot reach
 * backwards into an arrangement somebody made last year.
 *
 * quiz_attempt_type IS NOT MAPPED, exactly as on BoardingRequest. It is a
 * constant column pinned by ck_sub_quiz_type, existing only so fk_sub_attempt
 * can be composite -- (quiz_attempt_id, quiz_attempt_type) against
 * quiz_attempts (id, quiz_type) -- which is what makes it impossible to gate a
 * subscription with a personality attempt that cannot be failed. Nothing may
 * ever set it to anything else, so it gets no field somebody could assign.
 *
 * @DynamicUpdate for the reason AdoptionApplication has it: cancelling touches
 * two columns of twelve, and Hibernate's default UPDATE rewrites all of them.
 */
@Entity
@Table(name = "daycare_subscriptions")
@DynamicUpdate
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DaycareSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pet_id", nullable = false)
    private Long petId;

    /**
     * Must equal pets.owner_id at signup, checked in the service because a
     * CHECK constraint cannot read another table. Stored rather than derived
     * so a pet later rehomed does not rewrite who signed it up.
     */
    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "shelter_id", nullable = false)
    private Long shelterId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DaycareSlot slot;

    @Column(name = "monthly_price", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal monthlyPrice = BigDecimal.ZERO;

    /**
     * NOT NULL here, unlike BoardingRequest.quizAttemptId.
     *
     * That column is nullable because requests predating the boarding quiz
     * already existed when V6 ran. There are no subscriptions at all yet, so
     * there is no history to accommodate -- and a subscription leaves the pet
     * here every weekday indefinitely, which makes the questions about
     * temperament and vet authority matter more than they do for one stay, not
     * less.
     */
    @Column(name = "quiz_attempt_id", nullable = false)
    private Long quizAttemptId;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    /** NULL means ongoing with no agreed end. ck_sub_ended: set once it stops. */
    @Column(name = "end_date")
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private SubscriptionStatus status = SubscriptionStatus.ACTIVE;

    /** Database default, so never written from here. */
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
