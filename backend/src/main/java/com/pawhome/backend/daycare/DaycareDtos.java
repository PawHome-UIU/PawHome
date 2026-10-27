package com.pawhome.backend.daycare;

import com.pawhome.backend.pet.Species;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class DaycareDtos {

    private DaycareDtos() {}

    // ------------------------------------------------------------- reading

    /**
     * One subscription, as both the owner's list and the shelter's page show
     * it.
     *
     * @Builder for the reason BoardingRow has it: a positional record with
     * runs of adjacent Strings compiles cleanly with two of them swapped, and
     * the first anyone notices is the wrong shelter's name on a bill.
     *
     * monthlyPrice COMES FROM THE SUBSCRIPTION, NOT THE SHELTER. It is what
     * this customer agreed to; V13 marks the copy as a justified 3NF exception
     * precisely so a later price rise cannot reach backwards.
     */
    @Builder
    public record SubscriptionRow(
            Long id,
            Long petId,
            String petName,
            String petBreed,
            Species petSpecies,
            String petCoverPhotoPath,
            Long ownerId,
            String ownerName,
            String ownerEmail,
            Long shelterId,
            String shelterName,
            String shelterAddress,
            String shelterPhone,
            DaycareSlot slot,
            /** "09:00 - 12:00". Derived from the enum, never stored. */
            String slotLabel,
            BigDecimal monthlyPrice,
            LocalDate startDate,
            LocalDate endDate,
            SubscriptionStatus status,
            LocalDateTime createdAt,
            Integer quizScore,
            Integer quizMaxScore,
            Boolean quizPassed,
            /** How many monthly slips this subscription has generated so far. */
            int slipsIssued,

            // ---- today, so the staff list can offer the right button -------
            //
            // COMPUTED PER ROW rather than fetched as a second list the page
            // has to marry up by id. A subscription without today's state is a
            // row a staff member cannot act on, so it travels with it.
            /** Checked in today and not yet gone home. */
            boolean hereToday,
            /** The visit to check out, when hereToday. Null otherwise. */
            Long todayVisitId,
            /** Which room they were put in today. Null when not here. */
            String todaySuiteName
    ) {}

    /**
     * How full one slot is at one shelter.
     *
     * `taken` IS NOT A COLUMN. It is counted from the subscriptions currently
     * holding the slot, because occupancy is a fact about other rows, not a
     * number anybody maintains. A stored counter is the version that drifts.
     */
    public record SlotOccupancy(DaycareSlot slot, String label,
                                long taken, int capacity, long free) {}

    /**
     * One pet due in today, and whether it has arrived.
     *
     * "DUE" IS DERIVED, NOT STORED, AND THERE IS NO ATTENDANCE ROW FOR IT.
     * daycare_attendance records ARRIVALS -- a row appears when a pet is
     * checked in. So "expected but not here" is the absence of a row, which is
     * why it can only be a LEFT JOIN from the subscriptions: there is nothing
     * else to select.
     *
     * WHAT MAKES A SUBSCRIPTION DUE: ACTIVE, started on or before today, and
     * not ended before it. That is the same window MonthlyBillingJob bills
     * for, and it has to stay the same -- a shelter that is told to expect a
     * pet it is not billing for, or bills for one it is not told to expect,
     * has two answers to one question.
     *
     * NO WEEKDAY FILTER, and that is a real limitation rather than an
     * oversight: daycare_subscriptions records a band, not a pattern of days,
     * so nothing in the schema can say "Tuesdays and Thursdays". Every
     * subscription reads as every day. Staff can see who did not come; the
     * system cannot know whether that was expected.
     */
    public record DueTodayRow(
            Long subscriptionId,
            Long petId,
            String petName,
            Species petSpecies,
            String petCoverPhotoPath,
            String ownerName,
            String ownerEmail,
            DaycareSlot slot,
            String slotLabel,
            /** Whether a visit exists for today that has not been closed. */
            boolean arrived,
            /** Null until they arrive. Needed to check them out again. */
            Long visitId,
            String suiteName,
            LocalDateTime checkedInAt,
            /** True once they have arrived AND gone home again. */
            boolean goneHome
    ) {}

    /**
     * The staff arrivals board: this shelter, today.
     *
     * housingPlaces IS THE NUMBER THE BANDS ARE SOLD AGAINST. Every
     * SlotOccupancy says how many places a band has SOLD
     * (shelters.daycare_slot_capacity); this says how many the rooms can
     * actually house. They are set by different people on different screens --
     * an admin types the band number, staff build the rooms -- so showing them
     * apart is how a shelter notices it has promised more than it has.
     *
     * Only one band is on site at a time, so one shelter-wide total is the
     * honest comparison rather than a figure per band.
     */
    public record ArrivalsBoard(
            int housingPlaces,
            List<SlotOccupancy> slots,
            List<DueTodayRow> due
    ) {}

    /**
     * One shelter's daycare offer, for the signup page.
     *
     * SHELTERS WITH NO DAYCARE ARE NOT IN THIS LIST. daycare_slot_capacity
     * defaults to 20 but the price defaults to 0.00, and a shelter that has
     * never set a price is not running daycare -- showing it with a free
     * subscription button would be a bug the customer finds first.
     */
    public record DaycareOffer(Long id, String name, String city, String state,
                               BigDecimal monthlyPrice, int slotCapacity,
                               List<SlotOccupancy> slots) {}

    /**
     * One pet's day: when it arrived, which room, whether it has gone home.
     *
     * NO SHELTER FIELD. Every caller already knows the shelter -- staff see
     * their own and the board is drawn per shelter -- so carrying it would be
     * a column that is the same on every row of every response.
     */
    @Builder
    public record AttendanceRow(
            Long id,
            Long subscriptionId,
            Long petId,
            String petName,
            String ownerName,
            DaycareSlot slot,
            String slotLabel,
            LocalDate visitDate,
            Long suiteId,
            String suiteName,
            LocalDateTime checkedInAt,
            String checkedInByName,
            /** Null means the pet is still here. */
            LocalDateTime checkedOutAt,
            String checkedOutByName,
            /** Minutes on the premises: to now if still here, else to departure. */
            long minutesOnSite
    ) {}

    // ------------------------------------------------------------- writing

    /**
     * Signing up.
     *
     * NO price, NO status, NO owner. The owner is the caller; the status is
     * ACTIVE because nothing else makes sense for a new row; and the price is
     * read from the shelter server-side, because a price the browser sends is
     * a customer setting their own bill.
     *
     * quizAttemptId IS REQUIRED, unlike on a boarding request. V13 explains
     * why: that column is nullable only because stays predating the quiz
     * already existed when V6 ran, and there is no comparable history here.
     */
    /**
     * Dropping a pet off.
     *
     * THE ROOM IS CHOSEN NOW, not at signup -- which room is free depends on
     * who else is in the building this morning. The band is NOT a parameter:
     * it comes from the subscription, and fk_attendance_sub makes it
     * impossible for the stored row to claim a different one.
     */
    public record CheckInRequest(
            @NotNull(message = "Which subscription is this?") Long subscriptionId,
            @NotNull(message = "Put the pet in a room.") Long suiteId
    ) {}

    public record SubscribeRequest(
            @NotNull(message = "Choose which pet this is for.") Long petId,
            @NotNull(message = "Choose a shelter.") Long shelterId,
            @NotNull(message = "Choose a slot.") DaycareSlot slot,
            @NotNull(message = "Take the boarding quiz first.") Long quizAttemptId,
            /** Optional. Defaults to today; may not be in the past. */
            LocalDate startDate
    ) {}
}
