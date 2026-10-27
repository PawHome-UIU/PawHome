package com.pawhome.backend.boarding;

import com.pawhome.backend.billing.BillingService;
import com.pawhome.backend.billing.BillingSlip;
// Nested in BillingDtos, so the package wildcard would not reach it. Named
// here for the same reason BoardingDtos names it: `@Valid ChargeLine` in a
// generic argument has to be a simple name, or javac refuses it as a
// type-use annotation on a scoping construct.
import com.pawhome.backend.billing.BillingDtos.ChargeLine;
import com.pawhome.backend.daycare.DaycareSubscription;
import com.pawhome.backend.daycare.DaycareSubscriptionRepository;
import com.pawhome.backend.pet.Pet;
import com.pawhome.backend.pet.PetRepository;
import com.pawhome.backend.pet.PetStatus;
import com.pawhome.backend.pet.Species;
import com.pawhome.backend.quiz.QuizAttempt;
import com.pawhome.backend.quiz.QuizAttemptRepository;
import com.pawhome.backend.quiz.QuizType;
import com.pawhome.backend.notification.NotificationService;
import com.pawhome.backend.notification.NotificationType;
import com.pawhome.backend.security.AppUser;
import com.pawhome.backend.audit.AuditAction;
import com.pawhome.backend.audit.AuditService;
import com.pawhome.backend.security.ScopeGuard;
import com.pawhome.backend.shelter.Shelter;
import com.pawhome.backend.shelter.ShelterRepository;
import com.pawhome.backend.shelter.ShelterStatus;
import com.pawhome.backend.shelter.ShelterSuite;
import com.pawhome.backend.shelter.ShelterSuiteRepository;
import com.pawhome.backend.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.pawhome.backend.boarding.BoardingDtos.*;
// Nested in the repository interface, so a wildcard on the package would not
// reach it -- it has to be named.
import com.pawhome.backend.boarding.BoardingRequestRepository.SuiteLoad;

/**
 * Boarding: asking for a stay, judging it, and running it.
 *
 * READ THIS BEFORE TOUCHING accept().
 *
 * One animal cannot be in two places at once. Every other rule in this file is
 * ordinary validation; that one is a concurrency problem, and V1 says so at
 * length under CREATE TABLE boarding_requests. PostgreSQL would state it
 * declaratively:
 *
 *     EXCLUDE USING gist (pet_id WITH =, daterange(start,end) WITH &&)
 *                  WHERE (status IN ('ACCEPTED','ACTIVE'))
 *
 * MySQL has no equivalent. So the guarantee lives in a transaction instead:
 * SELECT the overlapping stays FOR UPDATE, decide, write, commit. The FOR
 * UPDATE is not an optimisation and not defensive habit -- it is the whole
 * mechanism. Without it two staff members clicking Accept in the same second
 * both read "no clashes" and both write, and the shelter finds out when two
 * families arrive with the same dog.
 *
 * WHAT COUNTS AS HOLDING THE DATES is BoardingStatus.holdsDates(): ACCEPTED
 * and ACTIVE, and nothing else. A REQUESTED stay reserves nothing, which is
 * why several people may ask for overlapping dates and only the first accept
 * wins. DECLINED and CANCELLED free the dates again.
 *
 * THE SUITE IS LOCKED TOO. Same argument, different resource: without
 * lockOverlappingForSuite two dogs go into Kennel 1 for the same week and
 * nothing objects, because shelter_suites has no notion of a date.
 *
 * MONEY IS COPIED, NOT JOINED. daily_rate and estimated_cost are written from
 * the shelter's rate at request time and never read from shelters again. V1
 * marks that as a justified 3NF exception, and it is the difference between a
 * quote and a moving target: a shelter raising its price must not thereby
 * raise the bill for a stay somebody already booked.
 */
@Service
@RequiredArgsConstructor
public class BoardingService {

    private static final Logger log = LoggerFactory.getLogger(BoardingService.class);

    private final BoardingRequestRepository requestRepository;
    private final BoardingInstructionRepository instructionRepository;
    private final BoardingUpdateRepository updateRepository;
    private final ShelterSuiteRepository suiteRepository;
    private final BoardingChargeRepository chargeRepository;
    private final DaycareSubscriptionRepository subscriptionRepository;
    private final ShelterRepository shelterRepository;
    private final PetRepository petRepository;
    private final QuizAttemptRepository attemptRepository;
    private final StorageService storage;
    private final NotificationService notifications;
    // V13. One direction only: this service calls into billing, billing never
    // calls back -- it takes the loaded entity instead. A cycle here is not a
    // style problem, it is Spring refusing to construct either bean.
    private final BillingService billing;
    private final JdbcTemplate jdbc;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;

    // ------------------------------------------------------------ the owner

    /**
     * Ask for a stay.
     *
     * The early overlap check here uses the SAME locking query as accept(),
     * which looks like overkill for what is only a courtesy message. It is
     * not: an unlocked read could tell the customer their dates are free at
     * the exact moment another transaction is booking them, and then the form
     * would have promised something. The transaction is short and the lock is
     * on the pet's own rows, so the cost is nothing.
     */
    @Transactional
    public BoardingRow request(RequestBoarding r) {
        Long me = scopeGuard.require().id();

        Pet pet = petRepository.findById(r.petId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such pet."));

        // V1: owner_id "must equal pets.owner_id at request time. Checked in
        // the service, because a CHECK constraint cannot read another table."
        if (pet.getOwnerId() == null || !pet.getOwnerId().equals(me)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You can only book boarding for your own pets.");
        }

        Shelter shelter = shelterRepository.findById(r.shelterId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such shelter."));
        if (shelter.getStatus() != ShelterStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    shelter.getName() + " is not taking boarding bookings at the moment.");
        }

        int nights = nightsBetween(r.startDate(), r.endDate());
        if (r.startDate().isBefore(LocalDate.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Drop-off cannot be in the past.");
        }

        Long attemptId = null;
        if (r.quizAttemptId() != null) {
            QuizAttempt attempt = attemptRepository.findById(r.quizAttemptId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "That boarding quiz attempt does not exist."));
            // Both checks matter and they fail differently. A foreign attempt
            // is somebody booking on the strength of another person's answers;
            // the wrong type would be caught by fk_boarding_attempt, but as a
            // constraint violation nobody could read.
            if (!attempt.getUserId().equals(me)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "That quiz attempt is not yours.");
            }
            if (attempt.getQuizType() != QuizType.BOARDING) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "A boarding request needs a boarding attempt, not a "
                        + attempt.getQuizType().name().toLowerCase() + " one.");
            }
            attemptId = attempt.getId();
        }

        List<BoardingRequest> clash =
                requestRepository.lockOverlappingForPet(pet.getId(), r.startDate(), r.endDate());
        if (!clash.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    pet.getName() + " is already booked in from "
                    + clash.get(0).getStartDate() + " to " + clash.get(0).getEndDate() + ".");
        }

        // AND THE OTHER KIND OF CLASH. Two stays overlapping is one animal in
        // two buildings; a stay overlapping a daycare subscription is one
        // animal both living at a shelter and being dropped off there.
        requireNoDaycareClash(pet, r.startDate(), r.endDate());

        BigDecimal rate = shelter.getBoardingRateDaily() == null
                ? BigDecimal.ZERO : shelter.getBoardingRateDaily();

        BoardingRequest saved = requestRepository.save(BoardingRequest.builder()
                .petId(pet.getId())
                .ownerId(me)
                .shelterId(shelter.getId())
                .quizAttemptId(attemptId)
                .startDate(r.startDate())
                .endDate(r.endDate())
                .status(BoardingStatus.REQUESTED)
                .emergencyContact(r.emergencyContact().trim())
                .vetCareAuthorised(r.vetCareAuthorised())
                .dailyRate(rate)
                .estimatedCost(rate.multiply(BigDecimal.valueOf(nights)))
                .build());

        if (r.instructions() != null) {
            for (InstructionRequest in : r.instructions()) {
                if (in == null || in.body() == null || in.body().isBlank()) continue;
                instructionRepository.save(BoardingInstruction.builder()
                        .boardingId(saved.getId())
                        .category(in.category())
                        .body(in.body().trim())
                        .build());
            }
        }

        log.info("User {} requested boarding for pet {} at shelter {} as {}",
                me, pet.getId(), shelter.getId(), saved.getId());
        return row(saved.getId());
    }

    /** The caller's own stays, soonest drop-off last. */
    @Transactional(readOnly = true)
    public List<BoardingRow> mine() {
        return jdbc.query(SELECT_ROW + " WHERE b.owner_id = ? ORDER BY b.start_date DESC",
                ROW_MAPPER, scopeGuard.require().id());
    }

    /**
     * Pull out.
     *
     * ALLOWED UNTIL THE PET IS ACTUALLY THERE. Cancelling a REQUESTED stay
     * costs the shelter nothing and cancelling an ACCEPTED one frees a suite
     * they can re-let; cancelling an ACTIVE one would mean the animal is in
     * the building and the paperwork says it went home. That last case is a
     * check-out, which is the shelter's to record.
     */
    @Transactional
    public BoardingRow cancel(Long id) {
        BoardingRequest b = load(id);
        scopeGuard.requireOwner(b.getOwnerId());

        if (b.getStatus() != BoardingStatus.REQUESTED && b.getStatus() != BoardingStatus.ACCEPTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A stay that is " + b.getStatus().name().toLowerCase()
                    + " cannot be cancelled.");
        }

        b.setStatus(BoardingStatus.CANCELLED);
        // WHEN, but not BY WHOM: reviewed_by is a shelter user, and putting the
        // customer's own id there would show their name in the shelter's
        // "decided by" column. Nobody reviewed this -- it just closed.
        b.setReviewedAt(LocalDateTime.now());
        // The suite goes back to the pool the moment the status stops holding
        // dates. Leaving it set would make the room look busy on every board.
        b.setSuiteId(null);
        requestRepository.save(b);
        return row(id);
    }

    // ----------------------------------------------------------- the shelter

    /** The shelter's board. Requests first, then the stays in date order. */
    @Transactional(readOnly = true)
    public List<BoardingRow> forShelter() {
        Long scope = scopeGuard.shelterScope();
        return jdbc.query(SELECT_ROW + """
                 WHERE (? IS NULL OR b.shelter_id = ?)
                 ORDER BY FIELD(b.status,'REQUESTED','ACTIVE','ACCEPTED','COMPLETED',
                                         'DECLINED','CANCELLED'),
                          b.start_date
                """, ROW_MAPPER, scope, scope);
    }

    @Transactional(readOnly = true)
    public BoardingCounts counts() {
        Long scope = scopeGuard.shelterScope();
        return jdbc.queryForObject("""
                SELECT SUM(b.status = 'REQUESTED')                        AS requested,
                       SUM(b.status = 'ACTIVE')                           AS active,
                       SUM(b.status = 'ACTIVE' AND b.end_date = CURDATE()) AS departing,
                       SUM(b.status = 'COMPLETED')                        AS completed
                  FROM boarding_requests b
                 WHERE (? IS NULL OR b.shelter_id = ?)
                """,
                (rs, i) -> new BoardingCounts(rs.getLong("requested"), rs.getLong("active"),
                        rs.getLong("departing"), rs.getLong("completed")),
                scope, scope);
    }

    /**
     * The rooms offered by the accept dialog, for ONE request's dates.
     *
     * Species-filtered here rather than in the browser: a dog run must not
     * appear in the list for a cat at all, because an option that exists only
     * to be refused on submit is a worse experience than one that was never
     * offered.
     */
    @Transactional(readOnly = true)
    public List<SuiteRow> suitesFor(Long boardingId) {
        BoardingRequest b = load(boardingId);
        scopeGuard.requireShelter(b.getShelterId());

        Pet pet = petRepository.findById(b.getPetId()).orElseThrow();

        // V16. One query for the whole shelter, indexed by suite id, rather
        // than a count per room in the loop below -- six rooms is six extra
        // round trips for a dropdown, and a shelter with forty is forty.
        Map<Long, Integer> used = requestRepository
                .suiteLoads(b.getShelterId(), b.getStartDate(), b.getEndDate())
                .stream()
                .collect(Collectors.toMap(SuiteLoad::getSuiteId, SuiteLoad::getPlacesUsed));

        return suiteRepository.findByShelterIdOrderByNameAsc(b.getShelterId()).stream()
                .filter(ShelterSuite::isActive)
                .filter(s -> takesSpecies(s, pet.getSpecies()))
                .map(s -> {
                    // getOrDefault, not get: suiteLoads returns a row for every
                    // suite of the shelter, so a miss would mean the room was
                    // created between the two reads. Empty is the right answer
                    // for a room that did not exist a moment ago.
                    int placesUsed = used.getOrDefault(s.getId(), 0);

                    // V17. THIS STAY DOES NOT COUNT AGAINST ITSELF. Once the
                    // dialog is also a MOVE dialog, the room the pet is
                    // already in would otherwise show as full because of the
                    // pet you are trying to move -- and a single-place kennel
                    // would be un-leaveable, since the one room a move must
                    // always be able to offer is the one being left.
                    boolean current = s.getId().equals(b.getSuiteId());
                    if (current) placesUsed = Math.max(0, placesUsed - 1);

                    return new SuiteRow(s.getId(), s.getName(), s.getSpecies(),
                            s.isActive(),
                            // "occupied" now means FULL. See BoardingDtos.
                            placesUsed >= s.getCapacity(),
                            placesUsed, s.getCapacity(), current);
                })
                .toList();
    }

    /**
     * Say yes, and hold the dates.
     *
     * THE FIVE STEPS, ALL INSIDE ONE TRANSACTION:
     *   1. lock the pet's overlapping stays and refuse if there are any;
     *   2. lock the SHELTER row and refuse if the building is already at its
     *      licensed capacity for these dates;
     *   3. lock the suite's overlapping stays and refuse if the ROOM is full;
     *   4. assign the room;
     *   5. move the status to ACCEPTED, which is what makes 1, 2 and 3 true
     *      for the next caller.
     *
     * Steps 1 to 3 are worthless without step 5 happening in the same
     * transaction: the locks are released at commit, so the row that makes the
     * next accept fail has to be written before they go.
     *
     * TWO CAPACITY REFUSALS, BECAUSE THEY ARE DIFFERENT PROBLEMS WITH
     * DIFFERENT REMEDIES. A full room means pick another room; a full shelter
     * means this stay cannot happen here at all, and the staff member's next
     * action is to decline the request with a reason -- which is why the
     * message says so, and why nothing new had to be built for it. NO_CAPACITY
     * has been in DeclineReason since V1.
     *
     * THE SHELTER CHECK RUNS EVEN WHEN NO ROOM IS CHOSEN. Accepting without a
     * suite is legal -- the room can be assigned later -- but it still commits
     * the shelter to housing the animal, so the headcount still applies.
     * Checking it only inside the `suiteId != null` branch would leave the one
     * path that skips the room as the way round the licence.
     */
    @Transactional
    public BoardingRow accept(Long id, AcceptRequest r) {
        AppUser me = scopeGuard.require();
        BoardingRequest b = load(id);
        scopeGuard.requireShelter(b.getShelterId());
        requireStatus(b, BoardingStatus.REQUESTED, "accepted");

        // LOADED ONCE, AT THE TOP, because three separate things below need it
        // -- the daycare clash guard, the species check on the chosen room,
        // and the audit line. It used to be fetched inline for the first and
        // declared INSIDE the `a suite was named` branch for the second, so
        // the third did not compile at all when a stay was accepted without a
        // room: `pet` simply did not exist on that path.
        Pet pet = petRepository.findById(b.getPetId()).orElseThrow();

        List<BoardingRequest> clash =
                requestRepository.lockOverlappingForPet(b.getPetId(), b.getStartDate(), b.getEndDate());
        if (!clash.isEmpty()) {
            BoardingRequest other = clash.get(0);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This pet is already booked from " + other.getStartDate() + " to "
                    + other.getEndDate() + ", so these dates cannot be accepted.");
        }

        // A SUBSCRIPTION MAY HAVE APPEARED SINCE THE REQUEST. The adopter was
        // refused at request() time if one existed then; nothing stopped them
        // taking one out afterwards, and the shelter must not commit to a stay
        // that has become impossible in the meantime.
        requireNoDaycareClash(pet, b.getStartDate(), b.getEndDate());

        // --- 2 of 5: is there room in the BUILDING at all? ---------------
        // The lock is on the shelters row and nothing here writes to it; see
        // ShelterRepository.lockById for why that is the right row to hold.
        Shelter shelter = shelterRepository.lockById(b.getShelterId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such shelter."));
        int held = requestRepository.countPlacesHeld(
                shelter.getId(), b.getStartDate(), b.getEndDate());
        if (held >= shelter.getCapacity()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    shelter.getName() + " is full for those dates — " + held
                    + " of " + shelter.getCapacity() + " places are already taken. "
                    + "Decline this request with \"no capacity\" so the owner can "
                    + "try another shelter.");
        }

        if (r != null && r.suiteId() != null) {
            ShelterSuite suite = suiteRepository.findById(r.suiteId()).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "No such suite."));
            if (!suite.getShelterId().equals(b.getShelterId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "That suite belongs to another shelter.");
            }
            if (!suite.isActive()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        suite.getName() + " is out of service.");
            }
            if (!takesSpecies(suite, pet.getSpecies())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        suite.getName() + " does not take a "
                        + pet.getSpecies().name().toLowerCase() + ".");
            }
            // --- 3 of 5: is there room in the ROOM? ----------------------
            // V16 turned this from a yes/no into a fraction. The query is
            // unchanged and so is the lock; what changed is that the SIZE of
            // the list is compared against shelter_suites.capacity instead of
            // being tested for emptiness. One dog already in a four-place
            // kennel no longer closes it.
            List<BoardingRequest> roomClash = requestRepository
                    .lockOverlappingForSuite(suite.getId(), b.getStartDate(), b.getEndDate());
            if (roomClash.size() >= suite.getCapacity()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        suite.getName() + " is full for those dates — "
                        + roomClash.size() + " of " + suite.getCapacity()
                        + " places are taken. Choose another room.");
            }
            b.setSuiteId(suite.getId());
        }

        b.setStatus(BoardingStatus.ACCEPTED);
        b.setReviewedBy(me.id());
        b.setReviewedAt(LocalDateTime.now());
        requestRepository.save(b);

        notifications.raise(b.getOwnerId(), NotificationType.BOARDING_DECIDED,
                "Your boarding request was accepted",
                "The shelter has confirmed " + b.getStartDate() + " to " + b.getEndDate()
                + (b.getSuiteId() == null ? "." : ", and assigned a suite."),
                "/app/boarding/" + id);

        audit.record(AuditAction.BOARDING_ACCEPTED, AuditAction.BOARDING, id,
                pet.getName() + "'s stay was accepted for " + b.getStartDate()
                + " to " + b.getEndDate() + ".");

        log.info("Boarding {} accepted by user {} into suite {}", id, me.id(), b.getSuiteId());
        return row(id);
    }

    @Transactional
    public BoardingRow decline(Long id, DeclineBoarding r) {
        AppUser me = scopeGuard.require();
        BoardingRequest b = load(id);
        scopeGuard.requireShelter(b.getShelterId());
        requireStatus(b, BoardingStatus.REQUESTED, "declined");

        b.setStatus(BoardingStatus.DECLINED);
        b.setReviewedBy(me.id());
        b.setReviewedAt(LocalDateTime.now());
        b.setDeclineMessage(r.message().trim());
        requestRepository.save(b);

        notifications.raise(b.getOwnerId(), NotificationType.BOARDING_DECIDED,
                "Your boarding request was declined",
                // The shelter's own sentence. Most declines are a capacity
                // problem the owner can work around by moving their dates, and
                // only the sentence says which.
                r.message().trim(),
                "/app/boarding/" + id);
        return row(id);
    }

    /**
     * The pet has arrived.
     *
     * TWO WRITES THAT MUST BOTH HAPPEN: the stay becomes ACTIVE and the pet
     * becomes BOARDING. A pet marked BOARDING with no active stay is a pet
     * nobody can account for; an active stay whose pet still reads ADOPTED
     * makes the shelter's own pet list lie about who is in the building.
     *
     * ck_pets_owner_when_placed is satisfied for free: BOARDING requires an
     * owner_id, and this pet has one -- that is why it could be booked at all.
     */
    @Transactional
    public BoardingRow checkIn(Long id) {
        AppUser me = scopeGuard.require();
        BoardingRequest b = load(id);
        scopeGuard.requireShelter(b.getShelterId());
        requireStatus(b, BoardingStatus.ACCEPTED, "checked in");

        // BOTH ENDS OF THE BOOKED RANGE, AND ONLY ONE OF THEM USED TO BE HERE.
        //
        // The end-date guard has always been here. The start-date one had not,
        // and its absence STRANDED ANIMALS. A stay booked for next week could
        // be checked in today; complete() then writes picked_up_on = today,
        // which is earlier than start_date, and ck_boarding_picked_up refuses
        // it -- correctly, because "collected before it arrived" is a bad row.
        // The result was a pet that could be checked in and then never checked
        // out, with the staff member getting a 500 every time they tried,
        // until the booked start date came round.
        //
        // THE CONSTRAINT WAS NEVER THE PROBLEM. It was the only thing telling
        // the truth. The hole was here, one layer up, where the impossible
        // state gets created.
        if (LocalDate.now().isBefore(b.getStartDate())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "These dates do not start until " + b.getStartDate()
                    + ", so checking in today would record a stay that begins "
                    + "before it was booked to. If the pet has arrived early, "
                    + "the booking needs its dates changed first.");
        }
        if (LocalDate.now().isAfter(b.getEndDate())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "These dates ended on " + b.getEndDate() + ". Book new ones "
                    + "rather than checking in against a stay that is over.");
        }

        Pet pet = petRepository.findById(b.getPetId()).orElseThrow();
        pet.setStatus(PetStatus.BOARDING);
        petRepository.save(pet);

        b.setStatus(BoardingStatus.ACTIVE);
        requestRepository.save(b);

        BoardingUpdate arrival = writeUpdate(b, UpdateType.ARRIVAL_CHECK_IN,
                pet.getName() + " has arrived safely and been checked in.", null, me.id());
        notifyOwner(b, pet.getName() + " has arrived", arrival.getNote());
        return row(id);
    }

    /**
     * The pet has gone home.
     *
     * The pet returns to whichever placed status it came from, never to
     * AVAILABLE: it still has an owner, and ck_pets_owner_when_placed would
     * reject AVAILABLE while owner_id is set -- the constraint correctly
     * refusing to let a check-out quietly put somebody's dog up for adoption.
     *
     * WHICH placed status is restingStatus()'s job. A pet this network rehomed
     * goes back to ADOPTED; one registered from a household goes back to
     * OWNED. Hard-coding ADOPTED here would relabel every customer's own pet
     * as adopted the first time they boarded it.
     *
     * THIS IS ALSO WHERE THE BILL IS WRITTEN, added in V13. Four writes, one
     * transaction:
     *
     *   1. the pet goes back to its resting status;
     *   2. the stay becomes COMPLETED and picked_up_on is stamped;
     *   3. a billing_slips row is issued for the nights actually stayed, plus
     *      any food, medication or grooming the staff member is charging for;
     *   4. a DEPARTURE update and the owner's notifications.
     *
     * ALL OR NONE. A slip that commits while the check-out rolls back is a
     * bill for a stay the system says never ended, and the customer has it in
     * their hand.
     *
     * picked_up_on IS STAMPED BEFORE THE SLIP IS ISSUED and that order is not
     * cosmetic -- BillingService reads it off this object to work out how many
     * nights to charge for. Setting it afterwards would bill every stay as if
     * it had been collected today by coincidence.
     */
    @Transactional
    public BoardingRow complete(Long id, CompleteBoarding r) {
        AppUser me = scopeGuard.require();
        BoardingRequest b = load(id);
        scopeGuard.requireShelter(b.getShelterId());
        requireStatus(b, BoardingStatus.ACTIVE, "completed");

        Pet pet = petRepository.findById(b.getPetId()).orElseThrow();
        pet.setStatus(PetStatus.restingStatus(pet.getShelterId()));
        petRepository.save(pet);

        b.setStatus(BoardingStatus.COMPLETED);

        // THE DAY IT ACTUALLY ENDED, FLOORED AT THE DAY IT BEGAN.
        //
        // ck_boarding_picked_up refuses a date before start_date and
        // deliberately allows one after end_date: a late collection is a real
        // thing, and the slip charges for it.
        //
        // THE FLOOR IS A SAFETY NET FOR ROWS THAT SHOULD NOT EXIST. checkIn()
        // now refuses to start a stay before its booked start date, so a
        // healthy row can never reach here with today < start_date. Rows
        // created before that guard existed still can, and without this they
        // are UNCHECKOUTABLE FOREVER -- every attempt is a constraint
        // violation surfacing as a 500, with the animal standing in reception.
        //
        // Clamping rather than refusing, because refusing helps nobody: the
        // pet is going home either way, and a stay recorded as its own minimum
        // length is a far better record than a stay that cannot be closed.
        // BillingService already floors units at 1 for exactly the same
        // reason; this is that floor applied one layer earlier.
        //
        // LOGGED AS A WARNING, because a row that needs clamping is a row
        // somebody should look at -- silence here would hide the very thing
        // this is compensating for.
        LocalDate today = LocalDate.now();
        LocalDate collected = today.isBefore(b.getStartDate()) ? b.getStartDate() : today;
        if (!collected.equals(today)) {
            log.warn("Stay {} was checked in before its start date ({} > {}); "
                    + "recording collection as the start date so it can be closed.",
                    id, b.getStartDate(), today);
        }
        b.setPickedUpOn(collected);
        requestRepository.save(b);

        // EVERYTHING CHARGED DURING THE STAY, PLUS ANYTHING ADDED AT THE
        // COUNTER. V17 gave staff somewhere to put the Tuesday worming tablet
        // the moment it happened; the check-out dialog is still there for what
        // is noticed while the owner is standing there, and both end up as
        // lines on the one bill.
        //
        // ACCRUED FIRST, IN THE ORDER THEY WERE ADDED. The bill then reads as
        // the fortnight happened, with the last-minute items after it, rather
        // than the two kinds interleaved by nothing in particular.
        //
        // COPIED, NOT LINKED. boarding_charges stays exactly as it was and
        // billing_slip_items becomes the frozen record -- the same freeze
        // daily_rate and unit_rate already get, so that correcting a charge
        // later cannot silently change what the customer was asked to pay.
        List<ChargeLine> lines = new ArrayList<>();
        for (ChargeRow c : chargeRows(id)) {
            lines.add(new ChargeLine(c.description(), c.quantity(), c.unitPrice()));
        }
        if (r != null && r.extras() != null) lines.addAll(r.extras());

        // Inside this transaction, on purpose -- see the class comment on
        // BillingService for why it takes the entity rather than the id.
        // Idempotent: a double-clicked Complete gets the existing slip back
        // rather than billing the customer twice, and uq_slip_boarding is the
        // guarantee behind that. Note what that means for these lines: the
        // SECOND click adds none, because it never reaches the insert.
        BillingSlip slip = billing.issueForBoarding(b, pet.getName(), me.id(), lines);

        BoardingUpdate departure = writeUpdate(b, UpdateType.DEPARTURE,
                pet.getName() + " has been collected. Thank you for boarding with us.",
                null, me.id());
        notifyOwner(b, pet.getName() + " has gone home", departure.getNote());

        audit.record(AuditAction.BOARDING_COMPLETED, AuditAction.BOARDING, id,
                pet.getName() + " went home; " + slip.getSlipNo() + " issued for "
                + slip.getTotal() + ".");

        log.info("Stay {} completed by staff {}; slip {} issued for {}",
                id, me.id(), slip.getSlipNo(), slip.getTotal());
        return row(id);
    }

    /**
     * Post a note to the owner.
     *
     * ONLY WHILE THE PET IS HERE, and never of the two automatic kinds. An
     * ARRIVAL_CHECK_IN written by hand would be an arrival note on a stay that
     * has not started; those two are written by checkIn() and complete()
     * because they are the record OF that event, not a comment about it.
     */
    @Transactional
    public UpdateRow postUpdate(Long id, UpdateType type, String note, MultipartFile photo) {
        AppUser me = scopeGuard.require();
        BoardingRequest b = load(id);
        scopeGuard.requireShelter(b.getShelterId());

        if (b.getStatus() != BoardingStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Updates can only be posted while the pet is staying with you.");
        }
        // THREE AUTOMATIC TYPES SINCE V18. Each is the RECORD OF an event
        // rather than a comment about one, and each is written by the method
        // that performs the event -- checkIn, complete and moveSuite. Posted
        // by hand they would be able to disagree with the thing they describe:
        // an arrival note on a stay that has not started, or a room change
        // that moved nobody.
        if (type == UpdateType.ARRIVAL_CHECK_IN
                || type == UpdateType.DEPARTURE
                || type == UpdateType.ROOM_CHANGE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Arrival, departure and room-change notes are written by "
                    + "checking the pet in, out, and moving it — so that they "
                    + "cannot disagree with where the pet actually is.");
        }
        if (note == null || note.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Write a note.");
        }

        String path = (photo == null || photo.isEmpty()) ? null
                : storage.store(photo, "boarding");
        BoardingUpdate saved = writeUpdate(b, type, note.trim(), path, me.id());
        notifyOwner(b, "An update about your pet", saved.getNote());

        // READ BACK, do not map the saved entity. posted_at is a database
        // default and the column is insertable=false, so the entity Hibernate
        // hands back after the insert still has null there -- and an update
        // whose timestamp is null sorts to nowhere and renders as blank.
        return updateRow(saved.getId());
    }

    private UpdateRow updateRow(Long updateId) {
        return jdbc.queryForObject(UPDATE_ROW_SQL + " WHERE u.id = ?", UPDATE_MAPPER, updateId);
    }

    // -------------------------------------------------------------- reading

    /**
     * One stay with its instructions and its updates.
     *
     * Readable by the owner OR by the shelter holding it, which is why this
     * cannot use requireOwner or requireShelter alone: the first would lock
     * staff out of their own board and the second would lock the customer out
     * of their own booking.
     */
    /**
     * Move a pet to a different room, mid-stay.                    [V17]
     *
     * WHY THIS WAS MISSING AND SHOULD NOT HAVE BEEN. A suite was assigned once,
     * at accept(), and was then the only room that stay could ever have. Every
     * ordinary reason to move an animal -- a kennel that needs cleaning, a dog
     * that will not settle next to the one it can hear, a room taken out of
     * service, two animals that turn out not to get on -- had no answer but
     * completing the stay and starting another.
     *
     * ACCEPTED OR ACTIVE, and both are right for different reasons. ACCEPTED
     * means the room is booked and the animal has not arrived: changing your
     * mind before it does is not even a move. ACTIVE is a real move, and the
     * one this exists for. A REQUESTED stay has no room to change; a COMPLETED
     * or CANCELLED one is history, and history does not get rearranged.
     *
     * EVERY CHECK accept() MAKES, BECAUSE IT IS THE SAME DECISION. Belongs to
     * this shelter, is in service, takes this species, and has a place free --
     * under the same lockOverlappingForSuite, held until the move commits.
     * Assigning a room is not safer for being the second time.
     *
     * EXCEPT FOR ONE: THE STAY DOES NOT COUNT AGAINST ITSELF. If the room
     * being moved INTO is the room the pet is already in, the lock returns
     * this stay's own row and a one-place kennel would refuse the move on the
     * strength of its current occupant -- who is the animal moving. So this
     * stay's id is excluded from the count, which also makes re-picking the
     * same room a harmless no-op instead of an error.
     *
     * THE OWNER IS TOLD. A room is what somebody rings up and asks about, so
     * a move they were not told about is a conversation where the shelter and
     * the customer disagree about where the animal is. Only while ACTIVE,
     * though: "we have moved Rex to Kennel 3" about a dog that is still at
     * home would be alarming rather than informative.
     */
    @Transactional
    public BoardingRow moveSuite(Long id, MoveSuite r) {
        AppUser me = scopeGuard.require();
        BoardingRequest b = load(id);
        scopeGuard.requireShelter(b.getShelterId());

        if (!b.getStatus().holdsDates()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A stay that is " + b.getStatus().name().toLowerCase()
                    + " has no room to change.");
        }

        ShelterSuite suite = suiteRepository.findById(r.suiteId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such suite."));
        if (!suite.getShelterId().equals(b.getShelterId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That suite belongs to another shelter.");
        }
        if (!suite.isActive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    suite.getName() + " is out of service.");
        }

        Pet pet = petRepository.findById(b.getPetId()).orElseThrow();
        if (!takesSpecies(suite, pet.getSpecies())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    suite.getName() + " does not take a "
                    + pet.getSpecies().name().toLowerCase() + ".");
        }

        // The same lock accept() takes, minus this stay. See the note above on
        // why excluding it is not a loophole but the point.
        long othersInRoom = requestRepository
                .lockOverlappingForSuite(suite.getId(), b.getStartDate(), b.getEndDate())
                .stream()
                .filter(other -> !other.getId().equals(b.getId()))
                .count();
        if (othersInRoom >= suite.getCapacity()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    suite.getName() + " is full for those dates — " + othersInRoom
                    + " of " + suite.getCapacity() + " places are taken. "
                    + "Choose another room.");
        }

        Long from = b.getSuiteId();
        if (suite.getId().equals(from)) {
            // Nothing to do, and saying so beats writing an identical row and
            // sending the owner a note about a move that did not happen.
            return row(id);
        }

        b.setSuiteId(suite.getId());
        requestRepository.save(b);

        if (b.getStatus() == BoardingStatus.ACTIVE) {
            String fromName = from == null ? null
                    : suiteRepository.findById(from).map(ShelterSuite::getName).orElse(null);
            BoardingUpdate moved = writeUpdate(b, UpdateType.ROOM_CHANGE,
                    pet.getName() + " has been moved to " + suite.getName()
                    + (fromName == null ? "." : " from " + fromName + "."),
                    null, me.id());
            notifyOwner(b, pet.getName() + " has changed room", moved.getNote());
        }

        log.info("Stay {} moved from suite {} to suite {} by staff {}",
                id, from, suite.getId(), me.id());
        return row(id);
    }

    /**
     * Refuse a stay that contradicts a daycare subscription.       [V17]
     *
     * TWO STATEMENTS ABOUT WHERE ONE ANIMAL LIVES. A subscription says the pet
     * is dropped off for a band and goes home; boarding says it sleeps in a
     * kennel. A pet cannot be handed in at nine in the morning if it woke up
     * there, and until now nothing said so -- an adopter could subscribe a pet
     * and book it in for a fortnight, and both screens would agree with
     * themselves.
     *
     * ANY SHELTER, NOT JUST THIS ONE. "Subscribed at A, boarding at B" is not
     * an improvement on both at A; it is two shelters each believing they have
     * the animal.
     *
     * CHECKED IN THREE PLACES because there are three ways in, and a rule with
     * two doors and one lock is not a rule:
     *
     *   request()    the adopter books. The message they need.
     *   accept()     the shelter says yes. A subscription can be taken out
     *                between the request and the decision, so the shelter
     *                must not commit to a stay that has become impossible.
     *   subscribe()  the other order, in DaycareService. Without it the rule
     *                is one-way and the same contradiction is reachable by
     *                doing the two things the other way round.
     *
     * THE MESSAGE NAMES THE REMEDY, because "not eligible" with no way
     * forward is the kind of refusal that becomes a phone call. Ending or
     * cancelling the subscription is something the owner can do themselves.
     */
    private void requireNoDaycareClash(Pet pet, LocalDate start, LocalDate end) {
        List<DaycareSubscription> subscribed =
                subscriptionRepository.lockPetSubscriptionsOver(pet.getId(), start, end);
        if (subscribed.isEmpty()) return;

        DaycareSubscription sub = subscribed.get(0);
        throw new ResponseStatusException(HttpStatus.CONFLICT,
                pet.getName() + " is signed up for daycare (" + sub.getSlot().label()
                + (sub.getEndDate() == null
                        ? ", ongoing from " + sub.getStartDate()
                        : ", " + sub.getStartDate() + " to " + sub.getEndDate())
                + "), so those dates are already accounted for. A pet cannot be "
                + "dropped off for a band and boarded overnight at the same time — "
                + "end or cancel the daycare subscription first.");
    }

    // ============================================================= expenses

    /**
     * Charge for something, while the animal is still here.       [V17]
     *
     * WHY THIS IS NOT PART OF CHECKING OUT. It used to be the only way: the
     * check-out dialog took a list of extras and BillingService wrote them as
     * slip lines. That made the bill a feat of memory -- a fortnight of
     * feeding, medication and the odd vet visit, entered by whoever was on
     * shift, and the person handing the animal back on Friday was not there on
     * Tuesday. So charges accrue as they happen and the slip collects them.
     *
     * ACTIVE ONLY, WHICH IS NARROWER THAN IT LOOKS. A stay that is merely
     * ACCEPTED has nothing to charge for -- the animal is not here, so it has
     * not eaten anything. A COMPLETED one has a slip, and uq_slip_boarding
     * means exactly one: adding a charge after it was issued would be a line
     * on a bill the customer has already been handed, or a line on nothing at
     * all. Both are worse than saying no.
     *
     * NO LOCK, AND NOTHING NEEDS ONE. Two staff members charging for two
     * things is two INSERTs of two different rows; there is no count, no
     * capacity and no uniqueness for them to race over. The only way to get a
     * duplicate is to type the same charge twice, which is a thing a person
     * did, not a thing the system let happen -- and removeCharge is how it is
     * undone.
     */
    @Transactional
    public ChargeRow addCharge(Long id, ChargeLine r) {
        AppUser me = scopeGuard.require();
        BoardingRequest b = load(id);
        scopeGuard.requireShelter(b.getShelterId());
        requireStatus(b, BoardingStatus.ACTIVE, "charged for");

        BoardingCharge saved = chargeRepository.save(BoardingCharge.builder()
                .boardingId(id)
                .description(r.description().trim())
                .quantity(r.quantity())
                .unitPrice(r.unitPrice())
                .addedBy(me.id())
                .build());

        // RE-READ, NOT `saved`. line_total and added_at are generated by the
        // database -- the entity Hibernate just persisted has nulls in both,
        // because nothing told it to go and look. Returning that would show a
        // staff member a charge with no total against it.
        chargeRepository.flush();
        ChargeRow row = chargeRows(id).stream()
                .filter(c -> c.id().equals(saved.getId()))
                .findFirst()
                .orElseThrow();

        log.info("Staff {} charged {} x {} ({}) to stay {}",
                me.id(), r.quantity(), r.unitPrice(), r.description(), id);
        return row;
    }

    /**
     * Take a charge back off.
     *
     * A REAL DELETE, and the same argument OwnerPetController makes for
     * removing a household pet: an application, an adoption and a stay all end
     * in a status because each is a record of something that happened to
     * somebody. A mistyped charge on a bill nobody has been given is not a
     * record of anything -- it is a typo, and a bill carrying "grooming 0.00 --
     * cancelled" is a worse bill than one that never mentioned it.
     *
     * ONCE THE SLIP IS ISSUED THIS IS IMPOSSIBLE, by the same ACTIVE check as
     * adding. The line is on the customer's bill by then, and correcting a bill
     * is a different act from correcting a note -- one that should leave a
     * trace this table has nowhere to put.
     */
    @Transactional
    public void removeCharge(Long id, Long chargeId) {
        AppUser me = scopeGuard.require();
        BoardingRequest b = load(id);
        scopeGuard.requireShelter(b.getShelterId());
        requireStatus(b, BoardingStatus.ACTIVE, "charged for");

        BoardingCharge charge = chargeRepository.findById(chargeId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such charge."));
        // Belongs to THIS stay. Without it, holding one boarding id would be a
        // licence to delete a charge off another by naming its id on this URL
        // -- the same hole requirePhotoOf closes in PetService.
        if (!charge.getBoardingId().equals(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such charge.");
        }

        chargeRepository.delete(charge);
        log.info("Staff {} removed charge {} from stay {}", me.id(), chargeId, id);
    }

    /** This stay's charges, with the database's own line totals. */
    private List<ChargeRow> chargeRows(Long boardingId) {
        return jdbc.query("""
                SELECT  c.id, c.description, c.quantity, c.unit_price,
                        c.line_total, c.added_at,
                        -- LEFT, because added_by is nullable: fk_charges_user
                        -- is ON DELETE SET NULL so a staff member who has
                        -- since left does not take the charge with them.
                        u.full_name AS added_by_name
                FROM        boarding_charges c
                LEFT JOIN   users u ON u.id = c.added_by
                WHERE   c.boarding_id = ?
                ORDER BY c.added_at ASC, c.id ASC
                """, (rs, i) -> new ChargeRow(
                        rs.getLong("id"),
                        rs.getString("description"),
                        rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("unit_price"),
                        rs.getBigDecimal("line_total"),
                        rs.getString("added_by_name"),
                        rs.getTimestamp("added_at").toLocalDateTime()),
                boardingId);
    }

    @Transactional(readOnly = true)
    public BoardingDetail detail(Long id) {
        BoardingRequest b = load(id);
        requireOwnerOrShelter(b);

        List<InstructionRow> instructions = instructionRepository
                .findByBoardingIdOrderByIdAsc(id).stream()
                .map(x -> new InstructionRow(x.getId(), x.getCategory(), x.getBody()))
                .toList();

        List<UpdateRow> updates = jdbc.query(
                UPDATE_ROW_SQL + " WHERE u.boarding_id = ? ORDER BY u.posted_at DESC, u.id DESC",
                UPDATE_MAPPER, id);

        return new BoardingDetail(row(id), instructions, updates, chargeRows(id));
    }

    // --------------------------------------------------------------- shared

    private BoardingRequest load(Long id) {
        return requestRepository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such boarding request."));
    }

    private void requireOwnerOrShelter(BoardingRequest b) {
        AppUser me = scopeGuard.require();
        if (me.id().equals(b.getOwnerId())) return;
        // Throws for staff at another shelter, and for any other adopter.
        scopeGuard.requireShelter(b.getShelterId());
    }

    /** One message shape for every "you cannot do that now" in this class. */
    private static void requireStatus(BoardingRequest b, BoardingStatus wanted, String verb) {
        if (b.getStatus() != wanted) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A stay that is " + b.getStatus().name().toLowerCase()
                    + " cannot be " + verb + ".");
        }
    }

    /** NULL species means the room takes anything -- V1 is explicit about it. */
    private static boolean takesSpecies(ShelterSuite suite, Species species) {
        return suite.getSpecies() == null
                || suite.getSpecies().name().equals(species.name());
    }

    private static int nightsBetween(LocalDate start, LocalDate end) {
        // ck_boarding_dates (end > start) says the same thing, and would say it
        // as a constraint violation. Here it is a sentence.
        if (!end.isAfter(start)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Collection has to be at least one night after drop-off.");
        }
        return (int) ChronoUnit.DAYS.between(start, end);
    }

    /**
     * Every note the shelter writes reaches the owner's bell.
     *
     * The point of the update feed is that somebody who left their animal with
     * strangers hears from them. An update posted into a page they have to
     * remember to open is half a feature.
     */
    private void notifyOwner(BoardingRequest b, String title, String body) {
        notifications.raise(b.getOwnerId(), NotificationType.BOARDING_UPDATE,
                title, body, "/app/boarding/" + b.getId());
    }

    private BoardingUpdate writeUpdate(BoardingRequest b, UpdateType type,
                                       String note, String photoPath, Long author) {
        return updateRepository.save(BoardingUpdate.builder()
                .boardingId(b.getId())
                .updateType(type)
                .note(note)
                .photoPath(photoPath)
                .postedBy(author)
                .build());
    }

    private BoardingRow row(Long id) {
        List<BoardingRow> found = jdbc.query(SELECT_ROW + " WHERE b.id = ?", ROW_MAPPER, id);
        if (found.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such boarding request.");
        }
        return found.get(0);
    }

    private static final String UPDATE_ROW_SQL = """
        SELECT u.id, u.update_type, u.note, u.photo_path, u.posted_at,
               a.full_name AS posted_by_name
          FROM boarding_updates u
          JOIN users a ON a.id = u.posted_by
        """;

    private static final RowMapper<UpdateRow> UPDATE_MAPPER = (rs, i) -> new UpdateRow(
            rs.getLong("id"),
            UpdateType.valueOf(rs.getString("update_type")),
            rs.getString("note"),
            rs.getString("photo_path"),
            rs.getString("posted_by_name"),
            rs.getTimestamp("posted_at").toLocalDateTime());

    private static final String SELECT_ROW = """
        SELECT  b.id, b.pet_id, p.name AS pet_name, p.breed AS pet_breed,
                p.species AS pet_species,
                (SELECT ph.file_path FROM pet_photos ph
                  WHERE ph.pet_id = p.id AND ph.is_cover = TRUE LIMIT 1) AS pet_cover_photo_path,
                b.owner_id, o.full_name AS owner_name, o.email AS owner_email,
                b.shelter_id, s.name AS shelter_name, s.address_line AS shelter_address,
                s.phone AS shelter_phone,
                b.suite_id, su.name AS suite_name,
                b.start_date, b.end_date, b.picked_up_on, b.status,
                b.emergency_contact, b.vet_care_authorised,
                b.daily_rate, b.estimated_cost, b.requested_at,
                r.full_name AS reviewed_by_name, b.reviewed_at, b.decline_message,
                q.score AS quiz_score, q.max_score AS quiz_max_score, q.passed AS quiz_passed
        FROM        boarding_requests b
        JOIN        pets           p  ON p.id  = b.pet_id
        JOIN        users          o  ON o.id  = b.owner_id
        JOIN        shelters       s  ON s.id  = b.shelter_id
        LEFT JOIN   shelter_suites su ON su.id = b.suite_id
        LEFT JOIN   users          r  ON r.id  = b.reviewed_by
        -- LEFT, because quiz_attempt_id is nullable: V6 notes a request may
        -- predate the quiz, and an inner join would hide those rows entirely.
        LEFT JOIN   quiz_attempts  q  ON q.id  = b.quiz_attempt_id
        """;

    private static final RowMapper<BoardingRow> ROW_MAPPER = (rs, i) -> {
        LocalDate start = rs.getDate("start_date").toLocalDate();
        LocalDate end   = rs.getDate("end_date").toLocalDate();
        return BoardingRow.builder()
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
                // Same argument as the SMALLINT note below: read as an object
                // to detect NULL, then widen, rather than casting to whatever
                // the driver happens to box a BIGINT as.
                .suiteId(rs.getObject("suite_id") == null ? null : rs.getLong("suite_id"))
                .suiteName(rs.getString("suite_name"))
                .startDate(start)
                .endDate(end)
                .pickedUpOn(rs.getDate("picked_up_on") == null ? null
                        : rs.getDate("picked_up_on").toLocalDate())
                .nights((int) ChronoUnit.DAYS.between(start, end))
                .status(BoardingStatus.valueOf(rs.getString("status")))
                .emergencyContact(rs.getString("emergency_contact"))
                .vetCareAuthorised(rs.getBoolean("vet_care_authorised"))
                .dailyRate(rs.getBigDecimal("daily_rate"))
                .estimatedCost(rs.getBigDecimal("estimated_cost"))
                .requestedAt(rs.getTimestamp("requested_at").toLocalDateTime())
                .reviewedByName(rs.getString("reviewed_by_name"))
                .reviewedAt(rs.getTimestamp("reviewed_at") == null ? null
                        : rs.getTimestamp("reviewed_at").toLocalDateTime())
                .declineMessage(rs.getString("decline_message"))
                // SMALLINT and nullable. Read as an object and widen -- a cast
                // to Integer relies on how the driver happens to box SMALLINT.
                .quizScore(rs.getObject("quiz_score") == null ? null
                        : ((Number) rs.getObject("quiz_score")).intValue())
                .quizMaxScore(rs.getObject("quiz_max_score") == null ? null
                        : ((Number) rs.getObject("quiz_max_score")).intValue())
                .quizPassed((Boolean) rs.getObject("quiz_passed"))
                .build();
    };
}
