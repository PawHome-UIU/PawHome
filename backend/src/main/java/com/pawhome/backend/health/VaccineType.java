package com.pawhome.backend.health;

import com.pawhome.backend.pet.Species;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One kind of shot. Maps `vaccine_types`, seeded by V2.
 *
 * species IS NULLABLE AND NULL MEANS "ANY", exactly as with shelter_suites:
 * Rabies and Deworming apply to everything, FVRCP only to cats. Defaulting the
 * null instead of handling it would offer a cat owner a distemper booster.
 */
@Entity
@Table(name = "vaccine_types")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VaccineType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String name;

    /** NULL = applies to every species. */
    @Enumerated(EnumType.STRING)
    @Column
    private Species species;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;
}
