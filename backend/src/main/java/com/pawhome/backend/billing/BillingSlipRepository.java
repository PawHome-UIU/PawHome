package com.pawhome.backend.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface BillingSlipRepository extends JpaRepository<BillingSlip, Long> {

    /**
     * The slip already issued for a stay, if there is one.
     *
     * uq_slip_boarding makes at most one possible, which is what lets this
     * return an Optional rather than a List. BoardingService.complete() calls
     * it before issuing so a double-clicked button gets the existing slip back
     * instead of a constraint violation the customer would see as a 500.
     *
     * THE UNIQUE KEY IS STILL THE GUARANTEE. This check and the insert are not
     * atomic with each other, so two genuinely simultaneous clicks can both
     * pass it -- and then one of them loses at the key. That is the correct
     * division of labour: the read makes the normal case pleasant, the
     * constraint makes the race safe.
     */
    @Query(value = """
           SELECT *
             FROM billing_slips
            WHERE boarding_request_id = :boardingRequestId
           """, nativeQuery = true)
    Optional<BillingSlip> findForBoarding(@Param("boardingRequestId") Long boardingRequestId);

    /**
     * The slip for one subscription in one month, if the job has already run.
     *
     * period_from IS THE MONTH KEY. It is always the first of the month for a
     * daycare slip, which is what makes uq_slip_period (subscription_id,
     * period_from) mean "one per month" rather than "one per arbitrary date".
     */
    @Query(value = """
           SELECT *
             FROM billing_slips
            WHERE subscription_id = :subscriptionId
              AND period_from     = :periodFrom
           """, nativeQuery = true)
    Optional<BillingSlip> findForSubscriptionMonth(@Param("subscriptionId") Long subscriptionId,
                                                   @Param("periodFrom") LocalDate periodFrom);
}
