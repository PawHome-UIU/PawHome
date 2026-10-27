package com.pawhome.backend.pet;

import jakarta.persistence.*;
import org.hibernate.annotations.DynamicUpdate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * An animal. Maps `pets` from V1__init.sql.
 *
 * SHELTER_ID IS THE SHELTER OF RECORD AND DOES NOT MOVE WHEN THE PET IS
 * ADOPTED. Reporting has to keep crediting the shelter that did the work, and
 * it is also the column every scope check reads -- so a pet leaving the
 * building must not quietly leave its shelter's books.
 *
 * NO is_vaccinated FIELD, because there is no such column: V1 says so in a
 * comment and means it. It is derived in v_pet_listing from the vaccination
 * records, and a stored third copy is how a pet ends up showing a green tick
 * beside an overdue shot.
 *
 * NO COLLECTIONS. Traits and photos are separate tables resolved in the
 * service, following the project's rule. Here it also stops a list of twenty
 * pets from becoming forty-one queries.
 *
 * WHY @DynamicUpdate.
 *
 * Without it Hibernate uses ONE cached UPDATE statement per entity, listing
 * every column, whatever actually changed. Approving an application changes
 * three fields -- status, reviewed_by, reviewed_at -- and the statement it
 * sent was:
 *
 *     update adoption_applications set applicant_address=?, applicant_city=?,
 *       applicant_email=?, applicant_id=?, applicant_name=?, applicant_phone=?,
 *       decline_message=?, decline_reason_code=?, eligibility_attempt_id=?,
 *       home_type=?, household_note=?, pet_experience=?, pet_id=?,
 *       quiz_score=?, reviewed_at=?, reviewed_by=?, status=?, time_at_home=?
 *      where id=?
 *
 * Eighteen columns rewritten to change three. It is correct but wasteful, and
 * it rewrites the applicant's own copied answers on every staff decision --
 * which is how a last-write-wins bug appears the day two people act at once.
 *
 * @DynamicUpdate makes Hibernate build the statement from the fields that are
 * actually dirty, so the same approval now sends:
 *
 *     update adoption_applications set reviewed_at=?, reviewed_by=?, status=?
 *      where id=?
 *
 * THE COST is that the statement can no longer be prepared once and reused --
 * Hibernate builds it per flush. On a table this wide, with this few writes,
 * that trade is obviously worth it. On a hot table with one changing column
 * it would not be.
 */
@DynamicUpdate
@Entity
@Table(name = "pets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Pet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shelter_id", nullable = false)
    private Long shelterId;

    @Column(nullable = false, length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Species species;

    @Column(nullable = false, length = 100)
    private String breed;

    /** ck_pets_age: must be > 0. Checked in the service for a readable message. */
    @Column(name = "age_value", nullable = false)
    private short ageValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "age_unit", nullable = false)
    private AgeUnit ageUnit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Sex sex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PetSize size;

    /** DECIMAL(5,2), nullable -- a shelter may simply not have weighed it. */
    @Column(name = "weight_kg", precision = 5, scale = 2)
    private BigDecimal weightKg;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private PetStatus status = PetStatus.AVAILABLE;

    /** NULL unless status is ADOPTED or BOARDING -- ck_pets_owner_when_placed. */
    @Column(name = "owner_id")
    private Long ownerId;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "is_neutered", nullable = false)
    @Builder.Default
    private boolean neutered = false;

    @Column(name = "is_microchipped", nullable = false)
    @Builder.Default
    private boolean microchipped = false;

    /**
     * DECIMAL(10,2), so BigDecimal -- never double. A fee in a binary float is
     * wrong by design, and this one appears on an invoice.
     */
    @Column(name = "adoption_fee", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal adoptionFee = BigDecimal.ZERO;

    /**
     * Incremented by the public detail route, which does not exist yet.
     *
     * Read-only from this module on purpose: it is a counter, and a staff edit
     * that saved the whole entity would write back whatever value it happened
     * to load, silently undoing every view since.
     */
    @Column(name = "view_count", nullable = false, insertable = false, updatable = false)
    @Builder.Default
    private int viewCount = 0;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    /** Both set by the column defaults, so read-only from Java. */
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
