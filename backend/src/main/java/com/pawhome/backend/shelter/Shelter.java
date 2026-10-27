package com.pawhome.backend.shelter;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A shelter. Maps the `shelters` table from V1__init.sql.
 *
 * ddl-auto is `validate`, so this class creates nothing -- it is checked
 * against what Flyway built, and a mismatch fails startup rather than quietly
 * altering the schema. Every column below therefore has to match V1 exactly,
 * lengths included.
 *
 * NO COLLECTION OF PETS, STAFF OR SUITES. The project's convention is that
 * foreign keys stay as ids and are resolved in the service when needed, and a
 * @OneToMany here would be the worst case of breaking it: `shelters` is the
 * root of the whole schema, so a collection would pull the entire database
 * behind any shelter that got loaded.
 *
 * CAPACITY IS A DENOMINATOR, NOT A COUNT. The "28 / 50" bar on the admin page
 * takes its numerator from a live COUNT of pets. Storing the current occupancy
 * here would be a second copy of a fact the pets table already owns, and it
 * would drift the first time a pet was adopted outside this service.
 */
@Entity
@Table(name = "shelters")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Shelter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Unique (uq_shelters_name). Two shelters cannot share a name. */
    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "logo_path", length = 255)
    private String logoPath;

    @Column(nullable = false, length = 160)
    private String email;

    @Column(nullable = false, length = 30)
    private String phone;

    @Column(name = "address_line", nullable = false, length = 200)
    private String addressLine;

    @Column(nullable = false, length = 80)
    private String city;

    @Column(nullable = false, length = 80)
    private String state;

    @Column(nullable = false, length = 20)
    private String zip;

    /** ck_shelters_capacity: must be > 0. The service checks first so the
     *  caller gets a sentence instead of a constraint-violation stack trace. */
    @Column(nullable = false)
    private int capacity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ShelterStatus status = ShelterStatus.PENDING;

    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * DECIMAL(10,2), so BigDecimal -- never double.
     *
     * Money in a binary float is wrong by design: 0.1 has no exact
     * representation, and a boarding stay is this rate multiplied by a night
     * count. BigDecimal is the only type that survives that arithmetic.
     */
    @Column(name = "boarding_rate_daily", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal boardingRateDaily = BigDecimal.ZERO;

    /**
     * What one daily daycare slot costs for a month. Added in V13.
     *
     * ZERO MEANS "WE DO NOT RUN DAYCARE", and that is load-bearing rather than
     * a convenient coincidence: the column has to have a default for every
     * shelter that existed before V13, and a shelter that has never set a price
     * has not opted in. DaycareService.offers() filters on > 0 for exactly this
     * reason -- filtering on capacity instead would list every shelter in the
     * network with a free subscription button, because capacity defaults to 20.
     *
     * BigDecimal for the reason boardingRateDaily is: this number gets
     * multiplied and totalled, and a binary float cannot survive that.
     */
    @Column(name = "daycare_monthly_price", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal daycareMonthlyPrice = BigDecimal.ZERO;

    /**
     * How many pets may hold the SAME slot at once. Added in V13.
     *
     * NOT `capacity`, WHICH ALREADY EXISTS AND MEANS SOMETHING ELSE. That one
     * counts kennel places for resident animals -- the denominator of the
     * "28 / 50" bar on the shelter page. A pet in the midday daycare slot goes
     * home at 15:00 and occupies no kennel, so the two numbers are about
     * different things and reusing one for both would make one of them wrong
     * on screen.
     */
    @Column(name = "daycare_slot_capacity", nullable = false)
    @Builder.Default
    private int daycareSlotCapacity = 20;

    /** Set by the column DEFAULT, so it is read-only from Java. */
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
