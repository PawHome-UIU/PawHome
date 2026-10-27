package com.pawhome.backend.application;

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
 * A completed adoption. Maps `adoptions`.
 *
 * uq_adoption_application (application_id) is what makes approving idempotent
 * at the database level: one application can produce at most one adoption, so
 * two reviewers clicking Approve at the same moment cannot create two.
 *
 * shelter_id IS COPIED, not read through the pet. The pet keeps its shelter of
 * record so this would usually agree -- but "which shelter did this adoption"
 * is a fact about an event in the past, and reporting must not change because
 * somebody edited a pet afterwards.
 */
@Entity
@Table(name = "adoptions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Adoption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    @Column(name = "pet_id", nullable = false)
    private Long petId;

    @Column(name = "adopter_id", nullable = false)
    private Long adopterId;

    @Column(name = "shelter_id", nullable = false)
    private Long shelterId;

    @Column(name = "adopted_on", nullable = false)
    private LocalDate adoptedOn;

    /** ck_adoptions_fee: >= 0. BigDecimal because it appears on a receipt. */
    @Column(name = "fee_paid", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal feePaid = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
