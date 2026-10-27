package com.pawhome.backend.billing;

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
 * A bill for a completed stay or a subscribed month. Maps `billing_slips`
 * from V13__daycare_and_billing_slips.sql.
 *
 * THIS IS NOT A PAYMENT. There is no amount paid, no method, no PENDING/PAID
 * flag and no gateway anywhere in this project. A slip records WHAT IS OWED
 * and stops; the money is handed over in cash at the counter. That was a
 * deliberate scope decision -- a payments subsystem was designed and dropped,
 * because a PAID column is a column somebody then has to keep true, and the
 * moment it exists this is a payment system by accident.
 *
 * IT IS A DOCUMENT, NOT A VIEW, which is why total, unit_rate and units are
 * stored instead of derived. The slip must still say what it said on the day
 * it was handed over, even after the shelter changes its prices -- the same
 * reason adoption_applications copies the applicant's address rather than
 * joining to users. What the customer was given is the record; what the price
 * happens to be today is not.
 *
 * NO SETTERS ARE EVER CALLED ON A SAVED SLIP. @Setter is here because Lombok's
 * builder and JPA want it, not because a slip is editable. Re-billing means
 * issuing a second slip, not rewriting the first.
 *
 * EXACTLY ONE OF boardingRequestId AND subscriptionId IS SET. ck_slip_source
 * enforces it. They are two real foreign keys rather than the
 * entity_type/entity_id pair audit_log uses, because that pattern looks like a
 * foreign key and enforces nothing.
 */
@Entity
@Table(name = "billing_slips")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BillingSlip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * What the customer quotes down the phone: PW-B-2026-09-000123.
     *
     * DERIVED FROM THE THING BEING BILLED, NOT COUNTED. See the note at the
     * bottom of V13 for the full argument; briefly, a running counter needs
     * either a lock on a counter table or MAX+1 (which two transactions read
     * identically), and it cannot come from this row's own id because MySQL
     * forbids a generated column from referring to an AUTO_INCREMENT column.
     * Deriving it from the boarding request or the subscription-and-month
     * makes it unique by construction, and makes a re-issue after a rollback
     * produce the SAME number rather than burning one.
     */
    @Column(name = "slip_no", nullable = false, length = 30)
    private String slipNo;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "shelter_id", nullable = false)
    private Long shelterId;

    /** Set for a boarding slip; NULL for a daycare one. */
    @Column(name = "boarding_request_id")
    private Long boardingRequestId;

    /** Set for a daycare slip; NULL for a boarding one. */
    @Column(name = "subscription_id")
    private Long subscriptionId;

    /**
     * What the slip covers.
     *
     * FOR BOARDING THIS IS THE ACTUAL STAY -- start_date to picked_up_on --
     * not the dates that were booked. V13 added that column for exactly this:
     * billing five nights to somebody who collected their dog on the third
     * morning is wrong, and they are standing at the counter holding the proof.
     */
    @Column(name = "period_from", nullable = false)
    private LocalDate periodFrom;

    @Column(name = "period_to", nullable = false)
    private LocalDate periodTo;

    /** Nights boarded, or months subscribed. ck_slip_units: always > 0. */
    @Column(nullable = false)
    private short units;

    @Column(name = "unit_rate", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitRate;

    /**
     * NOT CHECKED AGAINST units * unit_rate, and deliberately. A prorated first
     * month or a goodwill discount after a bad stay is a legitimate slip that
     * such a constraint would refuse. V13 says so above the table.
     */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal total;

    /** Database default, so never written from here. */
    @Column(name = "issued_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime issuedAt;

    /** NULL means the monthly job issued it, not a person. */
    @Column(name = "issued_by")
    private Long issuedBy;
}
