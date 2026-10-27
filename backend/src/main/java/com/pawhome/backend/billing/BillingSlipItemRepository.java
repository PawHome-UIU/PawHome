package com.pawhome.backend.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BillingSlipItemRepository extends JpaRepository<BillingSlipItem, Long> {

    /**
     * The lines of one bill, in the order they were entered.
     *
     * READS idx_slip_items (slip_id, sort_order) -- the filter and the sort are
     * that index, in that order, so nothing is sorted at run time.
     *
     * id IS THE TIE-BREAK. sort_order is a SMALLINT the caller supplies, and
     * two lines given the same number would otherwise come back in whatever
     * order the storage engine felt like, which is a bill that reshuffles
     * itself between two readings.
     */
    @Query(value = """
           SELECT *
             FROM billing_slip_items
            WHERE slip_id = :slipId
            ORDER BY sort_order, id
           """, nativeQuery = true)
    List<BillingSlipItem> findForSlip(@Param("slipId") Long slipId);
}
