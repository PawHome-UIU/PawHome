package com.pawhome.backend.daycare;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

public interface DaycareAttendanceRepository extends JpaRepository<DaycareAttendance, Long> {

    /**
     * Today's visit for one subscription, if the pet has been checked in.
     *
     * uq_visit_per_day (subscription_id, visit_date) makes at most one
     * possible, which is what lets this return an Optional. The service reads
     * it before checking in so a double-clicked button gets the existing visit
     * back instead of a constraint violation the staff member would see as a
     * 500 -- the same division of labour as BillingSlipRepository
     * .findForBoarding: the read makes the normal case pleasant, the unique key
     * makes the race safe.
     */
    @Query(value = """
           SELECT *
             FROM daycare_attendance
            WHERE subscription_id = :subscriptionId
              AND visit_date      = :onDate
           """, nativeQuery = true)
    Optional<DaycareAttendance> findVisit(@Param("subscriptionId") Long subscriptionId,
                                          @Param("onDate") LocalDate onDate);

    /**
     * How many daycare pets are in this room in this band right now.
     *
     * REPLACED findInSuiteBand, WHICH RETURNED AN Optional. That signature was
     * only honest while uq_suite_band (suite_id, visit_date, slot) existed to
     * guarantee at most one row -- V16 drops that key, because "one animal per
     * room per band" is exactly the rule a twenty-place cat loft breaks. With
     * the key gone the question stops being "is there one" and becomes "how
     * many", and an Optional asked of a table that can return nineteen rows
     * would have silently picked one of them.
     *
     * checked_out_at IS NULL IS THE WHOLE POINT. A pet collected at noon is
     * not occupying a place at three, and counting it would have the room
     * refusing animals it has space for. Note this is the one difference from
     * the key it replaces: uq_suite_band counted departed pets too, so a room
     * could not be reused within a band even after it emptied.
     *
     * READS idx_attendance_suite_band (suite_id, visit_date, slot,
     * checked_out_at) -- V16 built it with all four columns precisely so this
     * count never touches the table.
     */
    @Query(value = """
           SELECT COUNT(*)
             FROM daycare_attendance
            WHERE suite_id        = :suiteId
              AND visit_date      = :onDate
              AND slot            = :#{#slot.name()}
              AND checked_out_at IS NULL
           """, nativeQuery = true)
    int countPresentInSuiteBand(@Param("suiteId") Long suiteId,
                                @Param("onDate") LocalDate onDate,
                                @Param("slot") DaycareSlot slot);

    /**
     * Send a pet home.
     *
     * BOTH COLUMNS IN ONE STATEMENT because ck_attendance_out_pair requires it:
     * a departure time with nobody's name against it is refused, so setting one
     * and then the other would fail on the first of the two.
     *
     * `AND checked_out_at IS NULL` makes it a compare-and-set -- checking a pet
     * out twice updates nothing the second time and returns 0, rather than
     * quietly moving the departure time to whenever the button was pressed
     * again.
     *
     * @return 1 if this call sent the pet home, 0 if it had already gone.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
           UPDATE daycare_attendance
              SET checked_out_at = :at,
                  checked_out_by = :staffId
            WHERE id             = :id
              AND checked_out_at IS NULL
           """, nativeQuery = true)
    int checkOut(@Param("id") Long id,
                 @Param("at") LocalDateTime at,
                 @Param("staffId") Long staffId);
}
