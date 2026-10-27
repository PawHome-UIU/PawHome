package com.pawhome.backend.daycare;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface DaycareSubscriptionRepository extends JpaRepository<DaycareSubscription, Long> {

    // =====================================================================
    //  THE TWO LOCKING READS
    //
    //  LOCK ORDER IS FIXED AND IT MATTERS. DaycareService.subscribe() takes
    //  lockPetSlot() FIRST and lockSlotAtShelter() SECOND, always, in every
    //  path. Two transactions that take the same two locks in opposite
    //  orders deadlock -- InnoDB detects it and kills one, so it surfaces as
    //  a random 500 under load rather than as corruption, but it is still a
    //  bug. One order, written down here, is the fix.
    // =====================================================================

    /**
     * Is this pet already signed up for this slot somewhere?
     *
     * A PET MAY HOLD MORE THAN ONE SLOT -- morning and afternoon is a
     * reasonable thing to want. The same slot twice is not: an animal cannot
     * be at two shelters between 09:00 and 12:00, which is why this is not
     * filtered by shelter.
     *
     * V13 explains why this is not a UNIQUE key. In short: UNIQUE (pet_id,
     * slot) would also forbid re-subscribing to a slot after cancelling it,
     * and expressing "at most one ACTIVE row" needs the generated-column trick
     * the schema has already been told it overuses. So the rule lives here,
     * stated once, with the lock that makes it hold.
     *
     * FOR UPDATE, not a plain SELECT. Without it two clicks in the same second
     * both read "no rows" and both insert.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
           SELECT s FROM DaycareSubscription s
            WHERE s.petId  = :petId
              AND s.slot   = :slot
              AND s.status = com.pawhome.backend.daycare.SubscriptionStatus.ACTIVE
           """)
    List<DaycareSubscription> lockPetSlot(@Param("petId") Long petId,
                                          @Param("slot") DaycareSlot slot);

    /**
     * Everybody currently holding this slot at this shelter. THE CAPACITY
     * CHECK, and the reason it cannot be a constraint.
     *
     * "No more than daycare_slot_capacity pets in the morning slot" is a rule
     * ABOUT SEVERAL ROWS AT ONCE. A MySQL CHECK sees one row and cannot count
     * its siblings; a UNIQUE index cannot express "at most twenty". So the
     * rule lives in a transaction, exactly as the boarding overlap rule does
     * in BoardingRequestRepository.lockOverlappingForPet.
     *
     * WHY FOR UPDATE IS THE WHOLE POINT. The same SELECT without it is a
     * photograph: two owners subscribing in the same instant both read 19,
     * both decide there is room, and the shelter wakes up with 21 dogs. With
     * the lock the second transaction blocks on the first's row and gap locks
     * until it commits, then re-reads and sees 20.
     *
     * It must run inside a transaction to mean anything, so the only caller is
     * @Transactional.
     *
     * idx_sub_capacity (shelter_id, slot, status) is what keeps the lock
     * window short -- and what makes the gap lock cover exactly the rows this
     * rule is about, rather than the whole table.
     *
     * RETURNS THE ROWS, NOT A COUNT, deliberately: SELECT COUNT(*) ... FOR
     * UPDATE is an aggregate and does not lock in the way the caller needs.
     * The caller counts the list.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
           SELECT s FROM DaycareSubscription s
            WHERE s.shelterId = :shelterId
              AND s.slot      = :slot
              AND s.status    = com.pawhome.backend.daycare.SubscriptionStatus.ACTIVE
            ORDER BY s.id
           """)
    List<DaycareSubscription> lockSlotAtShelter(@Param("shelterId") Long shelterId,
                                                @Param("slot") DaycareSlot slot);

    /**
     * This pet's live subscriptions covering a date range.       [V17]
     *
     * DAYCARE AND BOARDING ARE MUTUALLY EXCLUSIVE, and nothing said so. A pet
     * could hold the 09:00-12:00 band at one shelter and be booked in for a
     * fortnight's boarding at the same time, which is not a scheduling clash
     * to be resolved on the day -- it is two contradictory statements about
     * where one animal lives. A boarded pet is asleep in a kennel at nine in
     * the morning; it cannot also be dropped off for the morning.
     *
     * NOT FILTERED BY SHELTER, on the same reasoning as lockPetSlot: an animal
     * cannot be in two buildings, so "subscribed at A, boarding at B" is no
     * better than both at A. It is worse, if anything -- two shelters each
     * believing they have the pet.
     *
     * OPEN-ENDED SUBSCRIPTIONS OVERLAP EVERYTHING AFTER THEIR START, and that
     * is the honest reading rather than a bug: end_date NULL means "until
     * cancelled", so a pet with a standing subscription genuinely is expected
     * every day and genuinely cannot go boarding. The remedy is to end or
     * cancel the subscription, which the owner can do themselves.
     *
     * FOR UPDATE, because the caller is about to write a row whose legality
     * depends on this answer. Without it, subscribing and booking in the same
     * instant both read "nothing there" and both succeed -- the same race
     * lockOverlappingForPet exists to close on the boarding side.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
           SELECT s FROM DaycareSubscription s
            WHERE s.petId  = :petId
              AND s.status = com.pawhome.backend.daycare.SubscriptionStatus.ACTIVE
              AND s.startDate <= :end
              AND (s.endDate IS NULL OR s.endDate >= :start)
            ORDER BY s.id
           """)
    List<DaycareSubscription> lockPetSubscriptionsOver(@Param("petId") Long petId,
                                                       @Param("start") LocalDate start,
                                                       @Param("end") LocalDate end);


    // =====================================================================
    //  PLAIN READS
    // =====================================================================

    /** One owner's subscriptions, newest first. Reads idx_sub_owner. */
    @Query(value = """
           SELECT *
             FROM daycare_subscriptions
            WHERE owner_id = :ownerId
            ORDER BY status = 'ACTIVE' DESC, start_date DESC, id DESC
           """, nativeQuery = true)
    List<DaycareSubscription> findForOwner(@Param("ownerId") Long ownerId);

    /**
     * Everything still running, for the monthly billing job.
     *
     * THE start_date FILTER MATTERS. A subscription starting on the 15th must
     * not be billed by the job that runs on the 1st of that month -- it did
     * not exist for any of the period being billed. Reads idx_sub_billing
     * (status, start_date), whose column order is exactly this predicate.
     */
    @Query(value = """
           SELECT *
             FROM daycare_subscriptions
            WHERE status      = 'ACTIVE'
              AND start_date <= :periodTo
            ORDER BY id
           """, nativeQuery = true)
    List<DaycareSubscription> findActiveOnOrBefore(@Param("periodTo") LocalDate periodTo);

    /**
     * One shelter's subscriptions, for the staff page.
     *
     * NO SLOT PARAMETER. The page groups by slot and shows all three at once,
     * so a per-slot endpoint would be three round trips to draw one screen,
     * and a nullable slot filter would be a parameter that is null on every
     * real call. The ORDER BY does the grouping: live rows first, then by
     * slot in enum order.
     */
    @Query(value = """
           SELECT *
             FROM daycare_subscriptions
            WHERE shelter_id = :shelterId
            ORDER BY status = 'ACTIVE' DESC,
                     FIELD(slot, 'MORNING', 'MIDDAY', 'AFTERNOON'),
                     start_date DESC
           """, nativeQuery = true)
    List<DaycareSubscription> findForShelter(@Param("shelterId") Long shelterId);


    // =====================================================================
    //  THE WRITES -- written out, not generated. See ApplicationRepository
    //  for the full argument; the short version is that an UPDATE built from
    //  a dirty entity exists nowhere you can read it.
    // =====================================================================

    /**
     * Stop a subscription.
     *
     * BOTH COLUMNS IN ONE STATEMENT because ck_sub_ended requires it: a row
     * that is not ACTIVE must carry an end_date. Setting the status first and
     * the date second would fail on the first of the two -- there is no
     * instant in between where the row is allowed to exist.
     *
     * `AND status = 'ACTIVE'` makes this a compare-and-set: cancelling twice
     * updates nothing the second time and returns 0, which the service turns
     * into a 409 rather than silently moving the end date.
     *
     * @return 1 if this call stopped it, 0 if it was already stopped.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
           UPDATE daycare_subscriptions
              SET status   = :#{#status.name()},
                  end_date = :endDate
            WHERE id       = :id
              AND status   = 'ACTIVE'
           """, nativeQuery = true)
    int stop(@Param("id") Long id,
             @Param("status") SubscriptionStatus status,
             @Param("endDate") LocalDate endDate);

    /**
     * Retire subscriptions whose agreed end date has passed.
     *
     * ONE STATEMENT FOR THE WHOLE SWEEP, however many rows match -- the loop
     * version would be one UPDATE per row. Run from the monthly job before it
     * bills, so a subscription that ended in August is not billed for
     * September.
     *
     * @return how many were retired.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
           UPDATE daycare_subscriptions
              SET status = 'EXPIRED'
            WHERE status = 'ACTIVE'
              AND end_date IS NOT NULL
              AND end_date < :onDate
           """, nativeQuery = true)
    int expireEndedBefore(@Param("onDate") LocalDate onDate);
}
