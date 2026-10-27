package com.pawhome.backend.daycare;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One pet, in the building, on one day. Maps `daycare_attendance` from V15.
 *
 * THIS IS THE FACT NOTHING RECORDED. A subscription says a pet COMES every
 * weekday; it never said the pet is here today, so the shelter's "in care"
 * figure counted animals on its books and was read by everyone as animals in
 * its building. One row per pet per day is the difference.
 *
 * checked_out_at NULL MEANS STILL HERE, and that is the whole "who is on site
 * now" query: visit_date = today AND checked_out_at IS NULL. It is also why
 * idx_attendance_present leads with those two columns.
 *
 * slot IS A COPY, and unlike the constant discriminators elsewhere in this
 * schema it is mapped, because it genuinely varies per row. What stops it
 * disagreeing with the subscription is fk_attendance_sub, which references
 * daycare_subscriptions (id, slot) rather than (id) -- the V6 trick again. The
 * copy exists because uq_suite_band (suite_id, visit_date, slot) needs it: the
 * band is what lets one room take a morning pet and an afternoon pet on the
 * same day.
 *
 * @DynamicUpdate because checking out touches two columns of nine.
 */
@Entity
@Table(name = "daycare_attendance")
@DynamicUpdate
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DaycareAttendance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DaycareSlot slot;

    @Column(name = "visit_date", nullable = false)
    private LocalDate visitDate;

    /**
     * Assigned at check-in, never at signup: a subscription is an agreement
     * about a time of day, and which room is free depends on who else is in
     * the building that morning.
     *
     * NOT NULL, because "here, room unknown" is not a state a shelter should
     * be able to record.
     */
    @Column(name = "suite_id", nullable = false)
    private Long suiteId;

    /** Database default, so never written from here. */
    @Column(name = "checked_in_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime checkedInAt;

    @Column(name = "checked_in_by", nullable = false)
    private Long checkedInBy;

    /** NULL while the pet is still here. ck_attendance_out_pair: both or neither. */
    @Column(name = "checked_out_at")
    private LocalDateTime checkedOutAt;

    @Column(name = "checked_out_by")
    private Long checkedOutBy;
}
