package com.pawhome.backend.boarding;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BoardingChargeRepository extends JpaRepository<BoardingCharge, Long> {

    /**
     * One stay's charges, oldest first.
     *
     * THE ONLY ORDER THIS TABLE IS EVER READ IN, which is why
     * idx_charges_boarding is (boarding_id, added_at) and not boarding_id
     * alone: the index answers the filter AND the sort, so a fortnight of
     * feeding never reaches a filesort.
     *
     * OLDEST FIRST, matching how BillingSlipItem.sortOrder works -- the order
     * things were typed in. A bill that reorders itself between the screen and
     * the printout is a bill somebody has to check twice.
     */
    @Query(value = """
           SELECT *
             FROM boarding_charges
            WHERE boarding_id = :boardingId
            ORDER BY added_at ASC, id ASC
           """, nativeQuery = true)
    List<BoardingCharge> findForBoarding(@Param("boardingId") Long boardingId);
}
