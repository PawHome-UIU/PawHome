package com.pawhome.backend.daycare;

import com.pawhome.backend.pet.Pet;
import com.pawhome.backend.pet.PetRepository;
import com.pawhome.backend.pet.Species;
import com.pawhome.backend.quiz.QuizAttempt;
import com.pawhome.backend.quiz.QuizAttemptRepository;
import com.pawhome.backend.quiz.QuizType;
import com.pawhome.backend.boarding.BoardingRequest;
import com.pawhome.backend.boarding.BoardingRequestRepository;
import com.pawhome.backend.security.AppUser;
import com.pawhome.backend.security.ScopeGuard;
import com.pawhome.backend.shelter.Shelter;
import com.pawhome.backend.shelter.ShelterRepository;
import com.pawhome.backend.shelter.ShelterStatus;
import com.pawhome.backend.shelter.ShelterSuite;
import com.pawhome.backend.shelter.ShelterSuiteRepository;
import com.pawhome.backend.shelter.SuiteSpecies;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.pawhome.backend.daycare.DaycareDtos.*;

/**
 * Daycare subscriptions: one pet, one daily slot, billed monthly.
 *
 * HOW THIS DIFFERS FROM BOARDING, AND WHY IT IS NOT THE SAME TABLE. A boarding
 * request is one stay with a start and an end, and it competes for a ROOM. A
 * subscription has no end, occupies no room, and competes for a PLACE IN A
 * TIME BAND. The two rules look alike -- both are "not too many at once" --
 * but they are counted against different things over different periods, and
 * folding them together would mean a boarding_requests row with a null
 * end_date meaning "forever", which is exactly the kind of overloaded column
 * this schema keeps refusing to write.
 *
 * WHAT MAKES SIGNUP HARD IS CAPACITY, AND CAPACITY IS NOT A CONSTRAINT.
 * "No more than twenty pets in the morning slot" is a rule about SEVERAL ROWS
 * AT ONCE. A MySQL CHECK sees one row; a UNIQUE index cannot express "at most
 * twenty". So the rule lives in a transaction, guarded by a pessimistic lock,
 * exactly as the boarding overlap rule does. Read
 * DaycareSubscriptionRepository.lockSlotAtShelter before changing subscribe().
 *
 * THE QUIZ IS A HARD GATE HERE. A per-night stay may carry a null
 * quiz_attempt_id because V6 found stays that predated the quiz. Nothing
 * predates this table, and a subscription leaves the animal here every weekday
 * indefinitely -- so the attempt is required, must belong to the caller, must
 * be a BOARDING attempt, and must have PASSED. The first three are also
 * enforced by fk_sub_attempt and ck_sub_quiz_type; they are checked here as
 * well so the customer gets a sentence instead of a constraint violation.
 */
@Service
@RequiredArgsConstructor
public class DaycareService {

    private static final Logger log = LoggerFactory.getLogger(DaycareService.class);

    private final DaycareSubscriptionRepository subscriptions;
    private final DaycareAttendanceRepository attendance;
    private final ShelterSuiteRepository suiteRepository;
    // Read-only here, and only for the lock: a room held overnight by a
    // boarder is not free for daycare, and that rule spans two tables.
    private final BoardingRequestRepository boardingRepository;
    private final PetRepository petRepository;
    private final ShelterRepository shelterRepository;
    private final QuizAttemptRepository attemptRepository;
    private final JdbcTemplate jdbc;
    private final ScopeGuard scopeGuard;

    // ------------------------------------------------------------ the offer

    /**
     * Every shelter running daycare, with live occupancy for all three slots.
     *
     * TWO QUERIES, NOT N+1. The obvious version loops the shelters and counts
     * each one's slots inside the loop, which is one query per shelter per
     * slot -- thirty round trips to draw ten cards. Here the counts arrive in
     * a single grouped query and are matched up in memory.
     */
    @Transactional(readOnly = true)
    public List<DaycareOffer> offers() {
        List<Object[]> shelters = jdbc.query("""
                SELECT id, name, city, state, daycare_monthly_price, daycare_slot_capacity
                  FROM shelters
                 WHERE status = 'ACTIVE'
                   -- A shelter that has never set a price is not running
                   -- daycare. The capacity column defaults to 20 for every
                   -- shelter, so capacity alone would list all of them with a
                   -- free subscription button.
                   AND daycare_monthly_price > 0
                   AND daycare_slot_capacity > 0
                 ORDER BY name
                """, (rs, i) -> new Object[]{
                        rs.getLong("id"), rs.getString("name"), rs.getString("city"),
                        rs.getString("state"), rs.getBigDecimal("daycare_monthly_price"),
                        rs.getInt("daycare_slot_capacity")});

        Map<Long, Map<DaycareSlot, Long>> taken = takenBySlot(null);

        List<DaycareOffer> out = new ArrayList<>(shelters.size());
        for (Object[] s : shelters) {
            Long id = (Long) s[0];
            int capacity = (Integer) s[5];
            out.add(new DaycareOffer(id, (String) s[1], (String) s[2], (String) s[3],
                    (BigDecimal) s[4], capacity,
                    occupancyFrom(taken.getOrDefault(id, Map.of()), capacity)));
        }
        return out;
    }

    /**
     * One shelter's three bands, as sold.
     *
     * NO LONGER ITS OWN ENDPOINT. GET /staff/daycare/occupancy was deleted at
     * V19: dueToday() returns these tiles alongside the arrivals board, so the
     * page fetches once and the numbers cannot disagree with the list beside
     * them. This method stays because dueToday and the adopter's offers() both
     * build on it.
     */
    @Transactional(readOnly = true)
    public List<SlotOccupancy> occupancy() {
        Long shelterId = myShelter();
        Shelter shelter = shelterRepository.findById(shelterId).orElseThrow();
        return occupancyFrom(takenBySlot(shelterId).getOrDefault(shelterId, Map.of()),
                shelter.getDaycareSlotCapacity());
    }

    /**
     * Who is due in today, who has arrived, and what the rooms hold.
     *
     * THE SCREEN THIS EXISTS FOR. Before it, the staff Daycare page had a
     * `Today` column in a table of EVERY subscription the shelter has ever
     * taken, ordered by status and start date. Everything needed to answer
     * "who are we still waiting for at half past nine" was on the page and
     * nowhere near each other.
     *
     * ONE QUERY, LEFT JOINED TO ATTENDANCE, because "expected and not here" is
     * the ABSENCE of a row -- daycare_attendance records arrivals, so there is
     * nothing to select for a pet that has not come. An inner join would show
     * only the pets that turned up, which is the one group nobody needs a list
     * of.
     *
     * TODAY'S VISIT INCLUDING A CLOSED ONE, unlike the correlated reads in
     * SELECT_ROW, which filter checked_out_at IS NULL. This board has three
     * states rather than two -- waiting, here, gone home -- and a pet
     * collected at noon is not the same as one that never came. uq_visit_per_day
     * (subscription_id, visit_date) is what keeps this join to one row.
     *
     * ORDERED BY BAND THEN BY WHO IS STILL MISSING. The morning band first
     * because it comes first, and within a band the pets nobody has seen at
     * the top, since they are the only rows that need doing something about.
     */
    @Transactional(readOnly = true)
    public ArrivalsBoard dueToday() {
        Long shelterId = myShelter();
        Shelter shelter = shelterRepository.findById(shelterId).orElseThrow();

        List<DueTodayRow> due = jdbc.query("""
                SELECT  d.id AS subscription_id, d.slot,
                        p.id AS pet_id, p.name AS pet_name, p.species,
                        (SELECT ph.file_path FROM pet_photos ph
                          WHERE ph.pet_id = p.id AND ph.is_cover = TRUE
                          LIMIT 1)                       AS pet_cover_photo_path,
                        o.full_name AS owner_name, o.email AS owner_email,
                        a.id            AS visit_id,
                        a.checked_in_at,
                        a.checked_out_at,
                        su.name         AS suite_name
                FROM        daycare_subscriptions d
                JOIN        pets  p ON p.id = d.pet_id
                JOIN        users o ON o.id = d.owner_id
                LEFT JOIN   daycare_attendance a
                        ON  a.subscription_id = d.id
                        AND a.visit_date      = CURDATE()
                LEFT JOIN   shelter_suites su ON su.id = a.suite_id
                WHERE   d.shelter_id = ?
                  AND   d.status     = 'ACTIVE'
                  AND   d.start_date <= CURDATE()
                  AND  (d.end_date IS NULL OR d.end_date >= CURDATE())
                ORDER BY FIELD(d.slot,'MORNING','MIDDAY','AFTERNOON'),
                         a.id IS NOT NULL,
                         p.name
                """, (rs, i) -> {
            DaycareSlot slot = DaycareSlot.valueOf(rs.getString("slot"));
            boolean hasVisit = rs.getObject("visit_id") != null;
            boolean goneHome = hasVisit && rs.getTimestamp("checked_out_at") != null;
            return new DueTodayRow(
                    rs.getLong("subscription_id"),
                    rs.getLong("pet_id"),
                    rs.getString("pet_name"),
                    Species.valueOf(rs.getString("species")),
                    rs.getString("pet_cover_photo_path"),
                    rs.getString("owner_name"),
                    rs.getString("owner_email"),
                    slot,
                    slot.label(),
                    // ARRIVED MEANS HERE NOW, so a pet collected at noon is
                    // not "arrived" at three -- it has gone home, which is the
                    // third state and its own flag.
                    hasVisit && !goneHome,
                    hasVisit ? rs.getLong("visit_id") : null,
                    rs.getString("suite_name"),
                    rs.getTimestamp("checked_in_at") == null ? null
                            : rs.getTimestamp("checked_in_at").toLocalDateTime(),
                    goneHome);
        }, shelterId);

        return new ArrivalsBoard(
                housingPlaces(shelterId),
                occupancyFrom(takenBySlot(shelterId).getOrDefault(shelterId, Map.of()),
                        shelter.getDaycareSlotCapacity()),
                due);
    }

    // --------------------------------------------------------- signing up

    @Transactional
    public SubscriptionRow subscribe(SubscribeRequest r) {
        Long me = scopeGuard.require().id();

        Pet pet = petRepository.findById(r.petId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such pet."));
        // The same rule boarding enforces, for the same reason: a CHECK cannot
        // read another table, so pets.owner_id is compared here.
        if (pet.getOwnerId() == null || !pet.getOwnerId().equals(me)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You can only sign up your own pets for daycare.");
        }

        Shelter shelter = shelterRepository.findById(r.shelterId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such shelter."));
        if (shelter.getStatus() != ShelterStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    shelter.getName() + " is not taking daycare bookings at the moment.");
        }

        BigDecimal price = shelter.getDaycareMonthlyPrice();
        int capacity = shelter.getDaycareSlotCapacity();
        if (price == null || price.signum() <= 0 || capacity <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    shelter.getName() + " does not run a daycare programme.");
        }

        QuizAttempt attempt = requirePassedBoardingAttempt(r.quizAttemptId(), me);

        LocalDate start = r.startDate() == null ? LocalDate.now() : r.startDate();
        if (start.isBefore(LocalDate.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A subscription cannot start in the past.");
        }

        // THE OTHER HALF OF A RULE BoardingService STATES.       [V17]
        //
        // A pet that is boarding is asleep in a kennel at nine in the morning;
        // it cannot also be dropped off for the morning band. BoardingService
        // .requireNoDaycareClash refuses the stay when the subscription came
        // first. This refuses the subscription when the stay did -- without
        // it the rule is one-way, and the same contradiction is reachable
        // simply by doing the two things in the other order.
        //
        // THE WINDOW RUNS TO THE FAR FUTURE, and that is not laziness. A
        // subscription is created open-ended -- SubscribeRequest has a start
        // date and no end date, because "every weekday until I say otherwise"
        // is what signing up means. So any stay at all from the start date
        // onwards collides with it, and the range this asks about is the range
        // the subscription actually claims.
        List<BoardingRequest> boarded = boardingRepository
                .lockOverlappingForPet(pet.getId(), start, LocalDate.of(9999, 12, 31));
        if (!boarded.isEmpty()) {
            BoardingRequest stay = boarded.get(0);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    pet.getName() + " is booked in for boarding from "
                    + stay.getStartDate() + " to " + stay.getEndDate()
                    + ", so they cannot also be signed up for daycare over those "
                    + "dates. A pet cannot be dropped off for a band and boarded "
                    + "overnight at the same time.");
        }

        // ---- the two locks, IN THIS ORDER, ALWAYS. See the repository: two
        // transactions taking them in opposite orders deadlock.

        if (!subscriptions.lockPetSlot(pet.getId(), r.slot()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    pet.getName() + " is already signed up for the "
                    + r.slot().name().toLowerCase() + " slot ("
                    + r.slot().label() + "). Pick a different slot, or cancel "
                    + "that subscription first.");
        }

        List<DaycareSubscription> holding =
                subscriptions.lockSlotAtShelter(shelter.getId(), r.slot());
        if (holding.size() >= capacity) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The " + r.slot().label() + " slot at " + shelter.getName()
                    + " is full (" + holding.size() + " of " + capacity + ").");
        }

        // TWO CAPACITY QUESTIONS, AND daycare_slot_capacity ONLY ANSWERS THE
        // FIRST. That column is what the shelter has decided to SELL in a
        // band; this asks whether the building can actually house it. They are
        // set by different people at different times -- an admin types the
        // number on the shelter form, staff build the rooms on theirs -- so
        // nothing keeps them in step except this.
        //
        // SECOND, NOT FIRST, deliberately: "the morning slot is full (8 of 8)"
        // is the ordinary answer and should be the one most people get. This
        // one means the shelter has oversold, which is a different problem and
        // a rarer sentence.
        //
        // INSIDE THE SAME LOCK. lockSlotAtShelter is already held, so the rows
        // counted here cannot move underneath it.
        requireRoomFor(shelter, r.slot(), pet.getSpecies());

        DaycareSubscription saved = subscriptions.save(DaycareSubscription.builder()
                .petId(pet.getId())
                .ownerId(me)
                .shelterId(shelter.getId())
                .slot(r.slot())
                .monthlyPrice(price)
                .quizAttemptId(attempt.getId())
                .startDate(start)
                .status(SubscriptionStatus.ACTIVE)
                .build());

        log.info("User {} subscribed pet {} to the {} slot at shelter {} as {}",
                me, pet.getId(), r.slot(), shelter.getId(), saved.getId());
        return row(saved.getId());
    }

    /** The caller's own subscriptions, live ones first. */
    @Transactional(readOnly = true)
    public List<SubscriptionRow> mine() {
        return jdbc.query(SELECT_ROW + """
                 WHERE d.owner_id = ?
                 ORDER BY d.status = 'ACTIVE' DESC, d.start_date DESC, d.id DESC
                """, ROW_MAPPER, scopeGuard.require().id());
    }

    /**
     * Stop a subscription.
     *
     * THE END DATE IS NOT SIMPLY TODAY. ck_sub_dates says end_date >=
     * start_date, and a subscription can be signed up to start next Monday and
     * cancelled the same afternoon -- writing today's date there would be
     * rejected by the database as a row that ended before it began. The later
     * of the two is what actually happened: it stopped on the day it was due
     * to start, having run for nothing.
     */
    @Transactional
    public SubscriptionRow cancel(Long id) {
        DaycareSubscription sub = load(id);
        scopeGuard.requireOwner(sub.getOwnerId());

        LocalDate today = LocalDate.now();
        LocalDate ended = today.isBefore(sub.getStartDate()) ? sub.getStartDate() : today;

        if (subscriptions.stop(id, SubscriptionStatus.CANCELLED, ended) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That subscription is already " + sub.getStatus().name().toLowerCase() + ".");
        }
        log.info("User {} cancelled subscription {} as of {}",
                scopeGuard.require().id(), id, ended);
        return row(id);
    }

    /**
     * Retire every subscription whose agreed end date has passed.
     *
     * CALLED BY MonthlyBillingJob BEFORE IT BILLS, and it lives HERE rather
     * than on the job for a reason worth stating: @Transactional works through
     * a Spring proxy, and a call from one method of a bean to another method
     * of the SAME bean never leaves the object, so it never passes through the
     * proxy and the annotation does nothing. The job calling its own
     * @Transactional helper would have run this @Modifying query with no
     * transaction at all -- which is not a subtle bug, it is a
     * TransactionRequiredException at 03:00 on the first of the month.
     *
     * Crossing a bean boundary is what makes the annotation real.
     *
     * ITS OWN TRANSACTION, not the billing one: a subscription that ended in
     * August must stay retired whatever happens to somebody else's slip.
     *
     * @return how many were retired.
     */
    @Transactional
    public int retireEnded(LocalDate onDate) {
        int retired = subscriptions.expireEndedBefore(onDate);
        if (retired > 0) log.info("Retired {} subscription(s) ended before {}", retired, onDate);
        return retired;
    }

    // ====================================================== the day itself

    /**
     * Drop a pet off: record that it is here, and put it in a room.
     *
     * TWO RULES, AND ONLY ONE OF THEM IS A CONSTRAINT.
     *
     *   1. One visit per subscription per day.  uq_visit_per_day.
     *   2. The room must have a place free.
     *          NOT EXPRESSIBLE AS A CONSTRAINT AT ALL. It counts rows in TWO
     *          tables -- daycare_attendance for the day pets, and
     *          boarding_requests over a DATE RANGE for whoever is staying the
     *          night -- against a column in a THIRD, shelter_suites.capacity.
     *          A CHECK may not contain a subquery, a UNIQUE key counts to
     *          exactly one, and MySQL has no EXCLUDE constraint. So the lock
     *          below is the guarantee.
     *
     * THIS USED TO BE TWO RULES AND V16 MERGED THEM. Before it, a room held
     * one daycare pet per band (uq_suite_band) and was off-limits entirely if
     * a boarder held it overnight. Both were the same assumption in different
     * clothes -- that a room is a place for ONE animal -- and both are wrong
     * about a cat loft with twenty places in it. Now there is one arithmetic
     * question, asked once: how many animals are in there, and does the room
     * have space for another. A boarder staying the night and a cat in for the
     * morning are two of the twenty, not two reasons to refuse.
     *
     * THE LOCK IS lockOverlappingForSuite, the one boarding already uses, and
     * it does double duty: it is how the overnight occupants get counted AND
     * how this path serialises against BoardingService.accept, which takes the
     * same lock on the same rows. Two staff members putting two cats in the
     * last place of Cat Loft A both read "nineteen" without it, and the
     * shelter discovers the twenty-first when it is carried in.
     *
     * IDEMPOTENT. Checking the same pet in twice gets the first visit back --
     * the read is the pleasant path, uq_visit_per_day is what makes the race
     * safe.
     */
    @Transactional
    public AttendanceRow checkIn(CheckInRequest r) {
        AppUser me = scopeGuard.require();
        DaycareSubscription sub = load(r.subscriptionId());
        scopeGuard.requireShelter(sub.getShelterId());

        if (sub.getStatus() != SubscriptionStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That subscription is " + sub.getStatus().name().toLowerCase()
                    + ", so the pet is not expected today.");
        }

        LocalDate today = LocalDate.now();
        if (today.isBefore(sub.getStartDate())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That subscription does not start until " + sub.getStartDate() + ".");
        }

        // Already here? Hand back the visit rather than a duplicate-key error.
        DaycareAttendance existing = attendance.findVisit(sub.getId(), today).orElse(null);
        if (existing != null) return attendanceRow(existing.getId());

        ShelterSuite suite = suiteRepository.findById(r.suiteId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such room."));
        // Cross-table, so no CHECK can say it -- see the note at the end of V15.
        if (!suite.getShelterId().equals(sub.getShelterId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That room belongs to another shelter.");
        }
        if (!suite.isActive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    suite.getName() + " is out of service.");
        }

        Pet pet = petRepository.findById(sub.getPetId()).orElseThrow();
        if (!takesSpecies(suite, pet.getSpecies())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    suite.getName() + " does not take a "
                    + pet.getSpecies().name().toLowerCase() + ".");
        }

        // RULE 2, IN TWO HALVES BECAUSE THE OCCUPANTS LIVE IN TWO TABLES.
        //
        // FOR UPDATE over today only -- a daycare pet is here for one band of
        // one day, so locking the whole subscription period would hold rows
        // nobody is competing for.
        List<BoardingRequest> heldOvernight =
                boardingRepository.lockOverlappingForSuite(suite.getId(), today, today);

        // Same band only. MORNING, MIDDAY and AFTERNOON do not overlap in
        // time, so a cat that comes in at nine is not in the room at three and
        // is not competing for the place. Counting every band of the day would
        // have a room refuse animals it is empty of.
        //
        // Departed pets are excluded by the query -- see
        // countPresentInSuiteBand. That is a genuine change from
        // uq_suite_band, which counted a pet collected at noon for the rest of
        // the band.
        int present = heldOvernight.size()
                + attendance.countPresentInSuiteBand(suite.getId(), today, sub.getSlot());

        if (present >= suite.getCapacity()) {
            // A boarder gets named first because "taken by an overnight stay"
            // is the more surprising of the two answers to a member of staff
            // looking at a room that appears empty at ten in the morning. No
            // second query for a pet name: the count is the reason and the
            // number is the evidence, and fetching a row on every check-in so
            // the rare failure can print a name is work in the wrong place.
            String who = !heldOvernight.isEmpty()
                    ? "an overnight stay"
                    : "other pets";
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    suite.getName() + " is full for the " + sub.getSlot().label()
                    + " band — " + present + " of " + suite.getCapacity()
                    + " places are taken, including " + who + ". Put "
                    + pet.getName() + " in a different room.");
        }

        DaycareAttendance saved = attendance.save(DaycareAttendance.builder()
                .subscriptionId(sub.getId())
                // Copied from the subscription and never from the caller: the
                // band is not theirs to choose, and fk_attendance_sub would
                // refuse it anyway.
                .slot(sub.getSlot())
                .visitDate(today)
                .suiteId(suite.getId())
                .checkedInBy(me.id())
                .build());

        log.info("Staff {} checked pet {} into suite {} for the {} band",
                me.id(), pet.getId(), suite.getId(), sub.getSlot());
        return attendanceRow(saved.getId());
    }

    /**
     * Send a pet home.
     *
     * The compare-and-set lives in the repository: checking out twice changes
     * nothing the second time rather than moving the departure to whenever the
     * button was pressed again.
     */
    @Transactional
    public AttendanceRow checkOut(Long attendanceId) {
        AppUser me = scopeGuard.require();
        DaycareAttendance visit = attendance.findById(attendanceId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such visit."));
        DaycareSubscription sub = load(visit.getSubscriptionId());
        scopeGuard.requireShelter(sub.getShelterId());

        if (attendance.checkOut(attendanceId, LocalDateTime.now(), me.id()) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That pet has already gone home.");
        }
        return attendanceRow(attendanceId);
    }

    /** Everyone who came in on one day, still here or not. */
    @Transactional(readOnly = true)
    public List<AttendanceRow> board(LocalDate onDate) {
        Long shelterId = myShelter();
        LocalDate day = onDate == null ? LocalDate.now() : onDate;
        return jdbc.query(SELECT_VISIT + """
                 WHERE v.shelter_id = ?
                   AND v.visit_date = ?
                 ORDER BY v.still_here DESC,
                          FIELD(v.slot,'MORNING','MIDDAY','AFTERNOON'),
                          v.checked_in_at
                """, VISIT_MAPPER, shelterId, day);
    }

    // ------------------------------------------------------------- shelter

    /** One shelter's subscriptions. STAFF sees their own; ADMIN must name one. */
    @Transactional(readOnly = true)
    public List<SubscriptionRow> forShelter(Long shelterId) {
        Long scope = scopeGuard.shelterScope();
        Long target = scope != null ? scope : shelterId;
        if (target == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Say which shelter's subscriptions you want.");
        }
        scopeGuard.requireShelter(target);
        return jdbc.query(SELECT_ROW + """
                 WHERE d.shelter_id = ?
                 ORDER BY d.status = 'ACTIVE' DESC,
                          FIELD(d.slot,'MORNING','MIDDAY','AFTERNOON'),
                          d.start_date DESC
                """, ROW_MAPPER, target);
    }

    @Transactional(readOnly = true)
    public SubscriptionRow get(Long id) {
        SubscriptionRow found = row(id);
        // Either it is yours, or it belongs to your shelter. Neither check
        // alone is right: an owner has no shelter, and staff are not the owner.
        if (!found.ownerId().equals(scopeGuard.require().id())) {
            scopeGuard.requireShelter(found.shelterId());
        }
        return found;
    }

    // ----------------------------------------------------------- internals

    /**
     * The caller's own shelter, or 403.
     *
     * STAFF ONLY BY CONSTRUCTION. An admin has no shelter_id
     * (ck_users_staff_shelter), so this refuses them -- which is right for
     * every screen built around "my rooms, my pets, today".
     */
    private Long myShelter() {
        Long shelterId = scopeGuard.require().shelterId();
        if (shelterId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Your account does not belong to a shelter.");
        }
        scopeGuard.requireShelter(shelterId);
        return shelterId;
    }

    /**
     * NULL SPECIES ON A SUITE MEANS "ANY", which is why this is not a plain
     * equals. Copied in shape from BoardingService, deliberately: two places
     * deciding what a room accepts must decide it the same way, and the day
     * they disagree is the day a cat is put in a kennel by one screen and
     * refused by the other.
     */
    private static boolean takesSpecies(ShelterSuite suite, Species species) {
        if (suite.getSpecies() == null) return true;
        return suite.getSpecies().name().equals(species.name());
    }

    private AttendanceRow attendanceRow(Long id) {
        List<AttendanceRow> found = jdbc.query(
                SELECT_VISIT + " WHERE v.attendance_id = ?", VISIT_MAPPER, id);
        if (found.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such visit.");
        }
        return found.get(0);
    }

    private DaycareSubscription load(Long id) {
        return subscriptions.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such subscription."));
    }

    private QuizAttempt requirePassedBoardingAttempt(Long attemptId, Long me) {
        QuizAttempt attempt = attemptRepository.findById(attemptId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "That boarding quiz attempt does not exist."));
        // Three separate failures with three different meanings. Two of them
        // would also be caught by fk_sub_attempt, but as a constraint
        // violation nobody outside the server could read.
        if (!attempt.getUserId().equals(me)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "That quiz attempt is not yours.");
        }
        if (attempt.getQuizType() != QuizType.BOARDING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Daycare needs a boarding attempt, not a "
                    + attempt.getQuizType().name().toLowerCase() + " one.");
        }
        // V6 made passed nullable so a PERSONALITY attempt could say "not
        // applicable" instead of "failed". A BOARDING attempt always has a
        // real answer, so a null here would be a broken row, and Boolean.TRUE
        // .equals handles both cases without an NPE.
        if (!Boolean.TRUE.equals(attempt.getPassed())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That boarding quiz was not passed. Daycare needs a pass.");
        }
        return attempt;
    }

    // =====================================================================
    //  CAN THE BUILDING ACTUALLY HOUSE WHAT THE BAND HAS SOLD?
    //
    //  THE GAP THIS CLOSES. There were two capacity systems and they did not
    //  talk to each other:
    //
    //    shelters.daycare_slot_capacity  counted SUBSCRIPTIONS, checked when
    //                                    somebody signs up.
    //    shelter_suites.capacity         counted ARRIVALS, checked at
    //                                    check-in, when a room is picked.
    //
    //  Nothing compared them, so a shelter could sell places it had nowhere
    //  to put. With the seeded data Evergreen Paws sells twenty places in the
    //  morning band and its dog rooms hold ten -- three kennels at two, plus
    //  the four-place quiet room. Twenty dogs subscribe; ten get in, and the
    //  shelter finds out when the eleventh is carried through the door.
    //
    //  WHY THE ANSWER IS NOT "RESERVE A SUITE AT SUBSCRIBE TIME". Nothing says
    //  WHICH room. A suite is picked by a staff member on the day, from what
    //  is free, which is the right way round -- reserving one months ahead for
    //  a pet that comes every weekday would hold it empty on the days the pet
    //  is off sick, and freeze a decision best made by looking at the
    //  building.
    //
    //  SO THE GUARANTEE IS PER BAND, NOT PER ROOM: never sell more places in a
    //  band than the rooms can house for the species that hold them. Once that
    //  is true, first-come-first-served on rooms at check-in is safe, because
    //  there is always a room left for the last arrival.
    // =====================================================================

    /**
     * Refuse a subscription the building could not deliver.
     *
     * THIS IS AN EXACT FEASIBILITY TEST, not an approximation, and it is worth
     * saying why a simpler one would have been wrong.
     *
     * Rooms come in four kinds: DOG-only, CAT-only, OTHER-only, and general
     * (species NULL, which takes anything -- ShelterService.takesSpecies).
     * The obvious check --
     *
     *     dogs subscribed  <=  dog rooms + general rooms
     *
     * -- is NECESSARY and NOT SUFFICIENT: it passes for a shelter with ten dog
     * places, ten cat places, four general, eleven dogs and eleven cats. Each
     * species clears its own bound by borrowing the same four general places,
     * and the pair of them needs eight.
     *
     * The second condition is what fixes it: everybody who cannot fit in a
     * room of their own species has to share the general rooms, so the
     * OVERFLOW has to fit too. Together the two are Hall's condition for this
     * shape, which is to say: exactly feasible, no packing left to luck.
     *
     * UNDER THE BAND LOCK, ALWAYS. The caller has already taken
     * lockSlotAtShelter, so the subscription rows this counts cannot change
     * underneath it -- two owners subscribing the last dog place in the same
     * instant are serialised on those rows, exactly as the band-capacity check
     * above them is.
     *
     * NOT A CONSTRAINT, AND IT COULD NOT BE. It counts rows of one table,
     * grouped by a column of a second, against sums over a third. A CHECK may
     * not contain a subquery; this is not even one subquery.
     *
     * BOARDING IS DELIBERATELY NOT SUBTRACTED. A boarder holding a kennel
     * tonight says nothing about a Tuesday in March, and there is no row for a
     * stay nobody has booked yet. So this is the STANDING promise -- the
     * shelter's own rooms against its own subscriptions -- and the day's
     * contention with boarders is settled where it is knowable, by
     * DaycareService.checkIn counting for real under a lock.
     */
    private void requireRoomFor(Shelter shelter, DaycareSlot slot, Species joining) {
        // Demand: who already holds this band, by the species that decides
        // which rooms they can use. Locked rows, see above.
        Map<Species, Integer> demand = new EnumMap<>(Species.class);
        jdbc.query("""
                SELECT p.species, COUNT(*) AS demand
                  FROM daycare_subscriptions d
                  JOIN pets p ON p.id = d.pet_id
                 WHERE d.shelter_id = ?
                   AND d.slot       = ?
                   AND d.status     = 'ACTIVE'
                 GROUP BY p.species
                """,
            (RowCallbackHandler) rs -> demand.put(
                    Species.valueOf(rs.getString("species")), rs.getInt("demand")),
            shelter.getId(), slot.name());

        // The pet asking to join is one more of its own kind.
        demand.merge(joining, 1, Integer::sum);

        // Supply: places, by the kind of room they are in. A NULL key is the
        // general pool -- EnumMap cannot hold a null key, which is the reason
        // it is a separate variable rather than a fourth entry.
        Map<Species, Integer> dedicated = new EnumMap<>(Species.class);
        int[] general = {0};
        jdbc.query("""
                SELECT su.species, SUM(su.capacity) AS places
                  FROM shelter_suites su
                 WHERE su.shelter_id = ?
                   AND su.is_active
                 GROUP BY su.species
                """,
            (RowCallbackHandler) rs -> {
                String kind = rs.getString("species");
                if (kind == null) general[0] += rs.getInt("places");
                else dedicated.put(Species.valueOf(kind), rs.getInt("places"));
            },
            shelter.getId());

        // CONDITION 1, per species: could this kind fit even with the whole
        // general pool to itself? Checked first because it produces the
        // clearer sentence -- it names one species and one number.
        int mine = demand.getOrDefault(joining, 0);
        int roomsForMine = dedicated.getOrDefault(joining, 0) + general[0];
        if (mine > roomsForMine) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    shelter.getName() + " does not have the rooms for another "
                    + joining.name().toLowerCase() + " in the " + slot.label()
                    + " band — " + mine + " would be signed up and its rooms hold "
                    + roomsForMine + ".");
        }

        // CONDITION 2, the overflow: everybody who cannot fit in a room of
        // their own species is competing for the same general rooms.
        int overflow = 0;
        for (Map.Entry<Species, Integer> e : demand.entrySet()) {
            overflow += Math.max(0, e.getValue() - dedicated.getOrDefault(e.getKey(), 0));
        }
        if (overflow > general[0]) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The " + slot.label() + " band at " + shelter.getName()
                    + " is full for the rooms available — " + overflow + " pets "
                    + "would need a general-purpose room and there are "
                    + general[0] + " places in them.");
        }
    }

    /**
     * How many daycare places this shelter's rooms could house at once, and
     * how many the band has sold. For the staff tiles and the admin form.
     *
     * SAME ARITHMETIC AS requireRoomFor, ASKED WITHOUT A PET. There is no
     * species to check a bound for, so the honest single number is the total
     * the building holds -- an upper bound every band is under.
     */
    @Transactional(readOnly = true)
    public int housingPlaces(Long shelterId) {
        Integer places = jdbc.queryForObject("""
                SELECT COALESCE(SUM(su.capacity), 0)
                  FROM shelter_suites su
                 WHERE su.shelter_id = ?
                   AND su.is_active
                """, Integer.class, shelterId);
        return places == null ? 0 : places;
    }

    /**
     * Live occupancy, as shelter -> slot -> count.
     *
     * ONE QUERY whether it is asked about one shelter or all of them: the
     * optional filter is a nullable parameter rather than two methods, for the
     * same reason ApplicationService.forShelter uses `? IS NULL OR`.
     */
    private Map<Long, Map<DaycareSlot, Long>> takenBySlot(Long shelterId) {
        Map<Long, Map<DaycareSlot, Long>> out = new HashMap<>();
        jdbc.query("""
                SELECT shelter_id, slot, COUNT(*) AS taken
                  FROM daycare_subscriptions
                 WHERE status = 'ACTIVE'
                   AND (? IS NULL OR shelter_id = ?)
                 GROUP BY shelter_id, slot
                """,
            // EXPLICITLY A RowCallbackHandler. The single-ResultSet lambda
            // also fits ResultSetExtractor, which Spring calls ONCE for the
            // whole result -- and a silently one-row occupancy map is a bug
            // that looks like empty shelters rather than like a mistake.
            (RowCallbackHandler) rs -> {
                out.computeIfAbsent(rs.getLong("shelter_id"),
                                    k -> new EnumMap<>(DaycareSlot.class))
                   .put(DaycareSlot.valueOf(rs.getString("slot")), rs.getLong("taken"));
            }, shelterId, shelterId);
        return out;
    }

    /** All three slots, always -- an empty one is information, not an absence. */
    private List<SlotOccupancy> occupancyFrom(Map<DaycareSlot, Long> taken, int capacity) {
        List<SlotOccupancy> slots = new ArrayList<>(DaycareSlot.values().length);
        for (DaycareSlot slot : DaycareSlot.values()) {
            long used = taken.getOrDefault(slot, 0L);
            slots.add(new SlotOccupancy(slot, slot.label(), used, capacity,
                    Math.max(0, capacity - used)));
        }
        return slots;
    }

    private SubscriptionRow row(Long id) {
        List<SubscriptionRow> found = jdbc.query(SELECT_ROW + " WHERE d.id = ?", ROW_MAPPER, id);
        if (found.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such subscription.");
        }
        return found.get(0);
    }

    /**
     * One visit, with every name attached -- READ FROM v_daycare_day.
     *
     * THIS WAS SIX JOINS WRITTEN OUT HERE. V19 moved them into the view,
     * because the same join is what the history board and the live board both
     * need, and two copies of an eight-table join is two chances to disagree
     * about what a visit is.
     *
     * The view computes minutes_on_site the same way this query did, and for
     * the same reason: "still here" has to be measured against NOW, and the
     * server clock is the one both ends already agree on.
     */
    private static final String SELECT_VISIT = """
        SELECT  v.attendance_id      AS id,
                v.subscription_id, v.slot, v.visit_date, v.suite_id,
                v.checked_in_at, v.checked_out_at,
                v.suite_name, v.pet_id, v.pet_name, v.owner_name,
                v.checked_in_by_name, v.checked_out_by_name,
                v.minutes_on_site
        FROM        v_daycare_day v
        """;

    private static final RowMapper<AttendanceRow> VISIT_MAPPER = (rs, i) -> {
        DaycareSlot slot = DaycareSlot.valueOf(rs.getString("slot"));
        return AttendanceRow.builder()
                .id(rs.getLong("id"))
                .subscriptionId(rs.getLong("subscription_id"))
                .petId(rs.getLong("pet_id"))
                .petName(rs.getString("pet_name"))
                .ownerName(rs.getString("owner_name"))
                .slot(slot)
                .slotLabel(slot.label())
                .visitDate(rs.getDate("visit_date").toLocalDate())
                .suiteId(rs.getLong("suite_id"))
                .suiteName(rs.getString("suite_name"))
                .checkedInAt(rs.getTimestamp("checked_in_at").toLocalDateTime())
                .checkedInByName(rs.getString("checked_in_by_name"))
                .checkedOutAt(rs.getTimestamp("checked_out_at") == null ? null
                        : rs.getTimestamp("checked_out_at").toLocalDateTime())
                .checkedOutByName(rs.getString("checked_out_by_name"))
                .minutesOnSite(rs.getLong("minutes_on_site"))
                .build();
    };

    private static final String SELECT_ROW = """
        SELECT  d.id, d.pet_id, p.name AS pet_name, p.breed AS pet_breed,
                p.species AS pet_species,
                (SELECT ph.file_path FROM pet_photos ph
                  WHERE ph.pet_id = p.id AND ph.is_cover = TRUE LIMIT 1) AS pet_cover_photo_path,
                d.owner_id, o.full_name AS owner_name, o.email AS owner_email,
                d.shelter_id, s.name AS shelter_name, s.address_line AS shelter_address,
                s.phone AS shelter_phone,
                d.slot, d.monthly_price, d.start_date, d.end_date, d.status, d.created_at,
                q.score AS quiz_score, q.max_score AS quiz_max_score, q.passed AS quiz_passed,
                -- Correlated, not a join. A join to billing_slips would return
                -- one row per slip and multiply the subscription itself.
                (SELECT COUNT(*) FROM billing_slips bs
                  WHERE bs.subscription_id = d.id) AS slips_issued,
                -- TODAY, as three correlated reads rather than a join: a join
                -- to daycare_attendance would be fine here (uq_visit_per_day
                -- means at most one row matches) but it would stop being fine
                -- the moment somebody widened the date filter, and then a
                -- subscription would appear once per visit.
                (SELECT v.id FROM daycare_attendance v
                  WHERE v.subscription_id = d.id AND v.visit_date = CURDATE()
                    AND v.checked_out_at IS NULL)                  AS today_visit_id,
                (SELECT su.name FROM daycare_attendance v
                   JOIN shelter_suites su ON su.id = v.suite_id
                  WHERE v.subscription_id = d.id AND v.visit_date = CURDATE()
                    AND v.checked_out_at IS NULL)                  AS today_suite_name
        FROM        daycare_subscriptions d
        JOIN        pets          p ON p.id = d.pet_id
        JOIN        users         o ON o.id = d.owner_id
        JOIN        shelters      s ON s.id = d.shelter_id
        -- INNER, unlike the boarding equivalent: quiz_attempt_id is NOT NULL
        -- here, so there is no ungated subscription for a LEFT JOIN to rescue.
        JOIN        quiz_attempts q ON q.id = d.quiz_attempt_id
        """;

    private static final RowMapper<SubscriptionRow> ROW_MAPPER = (rs, i) -> {
        DaycareSlot slot = DaycareSlot.valueOf(rs.getString("slot"));
        return SubscriptionRow.builder()
                .id(rs.getLong("id"))
                .petId(rs.getLong("pet_id"))
                .petName(rs.getString("pet_name"))
                .petBreed(rs.getString("pet_breed"))
                .petSpecies(Species.valueOf(rs.getString("pet_species")))
                .petCoverPhotoPath(rs.getString("pet_cover_photo_path"))
                .ownerId(rs.getLong("owner_id"))
                .ownerName(rs.getString("owner_name"))
                .ownerEmail(rs.getString("owner_email"))
                .shelterId(rs.getLong("shelter_id"))
                .shelterName(rs.getString("shelter_name"))
                .shelterAddress(rs.getString("shelter_address"))
                .shelterPhone(rs.getString("shelter_phone"))
                .slot(slot)
                .slotLabel(slot.label())
                .monthlyPrice(rs.getBigDecimal("monthly_price"))
                .startDate(rs.getDate("start_date").toLocalDate())
                .endDate(rs.getDate("end_date") == null ? null
                        : rs.getDate("end_date").toLocalDate())
                .status(SubscriptionStatus.valueOf(rs.getString("status")))
                .createdAt(rs.getTimestamp("created_at").toLocalDateTime())
                // SMALLINT and nullable. Read as an object and widen -- a cast
                // to Integer relies on how the driver happens to box SMALLINT.
                .quizScore(rs.getObject("quiz_score") == null ? null
                        : ((Number) rs.getObject("quiz_score")).intValue())
                .quizMaxScore(rs.getObject("quiz_max_score") == null ? null
                        : ((Number) rs.getObject("quiz_max_score")).intValue())
                .quizPassed((Boolean) rs.getObject("quiz_passed"))
                .slipsIssued(rs.getInt("slips_issued"))
                .todayVisitId(rs.getObject("today_visit_id") == null ? null
                        : rs.getLong("today_visit_id"))
                .todaySuiteName(rs.getString("today_suite_name"))
                // Derived from the id rather than carried separately: two
                // fields that can disagree about the same fact is one field
                // too many.
                .hereToday(rs.getObject("today_visit_id") != null)
                .build();
    };
}
