package com.pawhome.backend.health;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One shot, given or booked. Maps `vaccinations` from V1__init.sql.
 *
 * THIS TABLE IS WHY pets HAS NO is_vaccinated COLUMN. V1 says so where the
 * column would have gone: the flag is derived in v_pet_listing from these
 * rows -- at least one COMPLETED and nothing SCHEDULED past its due date --
 * and a stored third copy is how a pet ends up showing a green tick beside an
 * overdue shot. Everything here feeds that expression.
 *
 * administered_by IS FREE TEXT, NOT A USER. V1's comment: the vet who gave the
 * shot is usually not a system user. recorded_by is the staff member who typed
 * it in, and they are two different people often enough that conflating them
 * would lose the vet's name entirely.
 */
@Entity
@Table(name = "vaccinations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Vaccination {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pet_id", nullable = false)
    private Long petId;

    @Column(name = "vaccine_type_id", nullable = false)
    private Long vaccineTypeId;

    @Column(name = "administered_on", nullable = false)
    private LocalDate administeredOn;

    /** ck_vacc_next_due: null, or strictly after administered_on. */
    @Column(name = "next_due_on")
    private LocalDate nextDueOn;

    @Column(name = "administered_by", nullable = false, length = 120)
    private String administeredBy;

    @Column(name = "batch_lot", length = 60)
    private String batchLot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private VaccinationStatus status = VaccinationStatus.COMPLETED;

    @Column(name = "certificate_path", length = 255)
    private String certificatePath;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "recorded_by", nullable = false)
    private Long recordedBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
