package com.pawhome.backend.shelter;

// ONE IMPORT INTO THE daycare PACKAGE, and it is deliberate. DaycareSlot owns
// the band times -- its javadoc says they live there and nowhere else -- so the
// alternative was a CASE in the SQL below repeating "09:00 - 12:00", which is
// the duplication that enum exists to prevent. A type import is the smaller
// cost; nothing here injects a daycare bean, so there is no cycle to construct.
import com.pawhome.backend.daycare.DaycareSlot;
import com.pawhome.backend.audit.AuditAction;
import com.pawhome.backend.audit.AuditService;
import com.pawhome.backend.security.ScopeGuard;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.pawhome.backend.shelter.ShelterDtos.*;

/**
 * The shelter network.
 *
 * THREE AUDIENCES, ONE SERVICE, THREE DIFFERENT ANSWERS:
 *
 *   an admin          every shelter, every field, and may change them
 *   a staff member    their own shelter only -- enforced by ScopeGuard
 *   anonymous         a name and a city, for the registration dropdown
 *
 * The third one is why `options()` exists as a separate method returning a
 * separate DTO. A staff member has to pick their shelter BEFORE they have an
 * account, so that call cannot require a token -- and an endpoint reachable
 * without a token must not hand out a shelter's email, phone, capacity or
 * occupancy. Filtering the full row in the controller would leave one
 * `return` away from leaking it.
 *
 * THIS IS THE FIRST PLACE ScopeGuard IS CALLED FROM PRODUCTION CODE. It has
 * been written and unit-tested since the RBAC increment and nothing invoked
 * it until now.
 *
 * BE HONEST ABOUT WHAT THAT DOES AND DOES NOT PROVE. The staff branch of
 * requireShelter -- "this row belongs to another shelter, refuse" -- is still
 * not exercised by any route a staff member can reach. mine() passes the
 * caller's own shelterId, so the check can only pass; the suite methods do
 * run the real comparison, but they hang off /admin/**, where the caller is
 * always an admin and the guard returns immediately. The staff branch becomes
 * live with the first /api/v1/staff/** endpoint that takes a row id, and
 * until then row-level scoping is proven by ScopeGuardTest and by nothing
 * else. The calls below are here so that endpoint inherits a service that
 * already asks the right question.
 *
 * NOTHING HERE DELETES A SHELTER. Pets, staff accounts and completed adoptions
 * all reference it, and an adoption that happened last year still has to name
 * the shelter that handed the animal over. Leaving the network is a status
 * change; `deactivate()` is the whole of it.
 */
@Service
@RequiredArgsConstructor
public class ShelterService {

    private static final Logger log = LoggerFactory.getLogger(ShelterService.class);

    private final ShelterRepository shelterRepository;
    private final ShelterSuiteRepository suiteRepository;
    private final com.pawhome.backend.storage.StorageService storage;
    private final JdbcTemplate jdbc;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;

    // ------------------------------------------------------------- reading

    /**
     * Every shelter with its live counts, for the admin table.
     *
     * ONE QUERY, NOT ONE PLUS THREE PER ROW. The counts are correlated
     * subqueries rather than a loop calling count() per shelter: with five
     * shelters the difference is invisible, and it is exactly the shape that
     * turns into sixty round trips once the network grows. Written this way
     * from the start because nobody comes back to fix a listing that "works".
     *
     * The subqueries are also the reason this is not a repository method --
     * petCount and staffCount belong to no entity, they are facts about the
     * relationship, and a DTO projection is where they belong.
     */
    @Transactional(readOnly = true)
    public List<ShelterRow> list() {
        return jdbc.query("""
            SELECT  s.*,
                    (SELECT COUNT(*) FROM pets p
                      WHERE p.shelter_id = s.id
                        AND p.status <> 'ADOPTED')            AS pet_count,
                    (SELECT COUNT(*) FROM users u
                      WHERE u.shelter_id = s.id
                        AND u.role = 'STAFF'
                        AND u.is_active = TRUE)               AS staff_count,
                    (SELECT COUNT(*) FROM shelter_suites sc
                      WHERE sc.shelter_id = s.id)             AS suite_count,
                    -- PLACES, NOT ROOMS. suite_count is how many rooms exist;
                    -- this is how many animals they hold between them, which
                    -- is the number daycare_slot_capacity has to be set
                    -- against. Active rooms only: a retired one is not
                    -- somewhere anybody can be put.
                    (SELECT COALESCE(SUM(sp.capacity), 0) FROM shelter_suites sp
                      WHERE sp.shelter_id = s.id
                        AND sp.is_active)                     AS suite_places,
                    -- ANIMALS ACTUALLY IN THE BUILDING. Three sources, and
                    -- they do not overlap: a resident is one of ours awaiting
                    -- a home, a boarder is somebody's pet that has checked in,
                    -- a daycare pet is here for one band today.
                    --
                    -- This is NOT pet_count above. That one is the shelter's
                    -- books; both were rendered under the label "Pets in Care",
                    -- which is why accepting a boarding request appeared to
                    -- change nothing -- a boarded pet has no shelter_id and was
                    -- never on those books.
                    ((SELECT COUNT(*) FROM pets p2
                       WHERE p2.shelter_id = s.id
                         AND p2.status IN ('AVAILABLE','PENDING'))
                   + (SELECT COUNT(*) FROM boarding_requests b2
                       WHERE b2.shelter_id = s.id
                         AND b2.status = 'ACTIVE')
                   + (SELECT COUNT(*) FROM daycare_attendance a2
                       JOIN daycare_subscriptions ds2 ON ds2.id = a2.subscription_id
                       WHERE ds2.shelter_id     = s.id
                         AND a2.visit_date      = CURDATE()
                         AND a2.checked_out_at IS NULL)) AS on_site_count
            FROM      shelters s
            ORDER BY  s.name
            """, this::mapRow);
    }

    @Transactional(readOnly = true)
    public ShelterRow get(Long id) {
        return one(id);
    }

    /**
     * The signed-in staff member's own shelter.
     *
     * The null check above is what refuses an admin: they have no shelter_id
     * (ck_users_staff_shelter), so "mine" means nothing for them and they read
     * the same data through list() and get().
     *
     * The requireShelter call that follows is deliberate but weak -- it is
     * handed the caller's own id, so for a staff member it cannot fail. It is
     * there so that the day this method takes a shelter id from anywhere else,
     * the guard is already in the path rather than being remembered.
     */
    @Transactional(readOnly = true)
    public ShelterRow mine() {
        return one(myShelterId());
    }

    /**
     * Which shelter the caller belongs to, or a refusal.
     *
     * Every "my shelter" method below routes through this ONE method rather
     * than reading scopeGuard.require().shelterId() for itself. That is not
     * tidiness: it means the id a staff member operates on can only ever come
     * from their own token, so no amount of adding endpoints can introduce a
     * path where a shelter id arrives from the URL and is trusted. The
     * requireShelter call is the same deliberate-but-weak check mine() always
     * had -- handed the caller's own id it cannot fail, and it is here so the
     * guard is already in the path if that ever stops being true.
     */
    private Long myShelterId() {
        Long shelterId = scopeGuard.require().shelterId();
        if (shelterId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Your account does not belong to a shelter.");
        }
        scopeGuard.requireShelter(shelterId);
        return shelterId;
    }

    // ------------------------------------------- a staff member's own suites
    //
    // Four thin wrappers over the admin suite methods. They exist because the
    // boarding accept dialog picks a suite from this list, so a shelter that
    // builds a new kennel or takes one out for repairs needed an admin to
    // record it -- for a room only its own staff can see. The wrappers add no
    // rules of their own; they substitute myShelterId() for the path
    // parameter, and the scoping, the duplicate-name check and the
    // deactivate-never-delete rule all come from the methods below unchanged.

    @Transactional(readOnly = true)
    public List<SuiteRow> mySuites() {
        return suites(myShelterId());
    }

    @Transactional
    public SuiteRow addMySuite(SuiteRequest r) {
        return addSuite(myShelterId(), r);
    }

    @Transactional
    public SuiteRow updateMySuite(Long suiteId, SuiteRequest r) {
        return updateSuite(myShelterId(), suiteId, r);
    }

    @Transactional
    public SuiteRow deactivateMySuite(Long suiteId) {
        return deactivateSuite(myShelterId(), suiteId);
    }

    /**
     * The registration dropdown. NO TOKEN REQUIRED -- see the class comment.
     *
     * ACTIVE only. Offering a PENDING shelter would let a staff member attach
     * themselves to one an admin has not yet cleared, and an INACTIVE one
     * would let them join a shelter that has left the network.
     */
    @Transactional(readOnly = true)
    public List<ShelterOption> options() {
        return shelterRepository.findByStatusOrderByNameAsc(ShelterStatus.ACTIVE)
                .stream()
                .map(s -> new ShelterOption(s.getId(), s.getName(), s.getCity(), s.getState(),
                        s.getBoardingRateDaily()))
                .toList();
    }

    // ================================================= the building, now

    /**
     * Every room, how full it is, and who is the face of it right now.
     *
     * ONE ROW PER ROOM, WHICH V16 MADE A THING THAT HAS TO BE ARRANGED. This
     * query used to LEFT JOIN boarding_requests and daycare_attendance
     * directly, and got away with it because the schema allowed at most one
     * occupant of each kind: any more and a LEFT JOIN multiplies, so Cat Loft
     * A with three cats in it would have appeared in the room list three
     * times. Now that a room holds twenty, both joins are pinned to a SINGLE
     * id chosen by a correlated subquery -- the earliest arrival -- so the
     * cardinality is a property of the query rather than a bet on the data.
     *
     * TWO NUMBERS AND A NAME, BECAUSE THEY ANSWER DIFFERENT QUESTIONS.
     * `occupied` / `capacity` is how full the room is, which is what decides
     * whether the next animal can go in it. petName is who to picture when
     * reading the line. Neither substitutes for the other: "Cat Loft A --
     * Mittens" is equally true with one cat and with twenty.
     *
     * occupied IS COUNTED ON EVERY READ AND STORED NOWHERE. A stored occupancy
     * column is wrong the first time any path checks an animal out without
     * decrementing it, and there are four such paths (check-out, early
     * pick-up, cancellation, the nightly job).
     *
     * RESERVED IS ITS OWN STATE. A stay that is ACCEPTED holds the room but
     * the animal has not arrived; a stay that is ACTIVE means it has. Treating
     * the first as free is how a room gets double-booked, and treating it as
     * occupied is how a shelter thinks it is full when it has space this
     * afternoon. So the state says which -- and note that a RESERVED room
     * still counts towards `occupied`, because the place is spoken for.
     *
     * nights_so_far IS COMPUTED, NOT STORED. It is the answer to "how long has
     * this one been with us", and storing it would mean a column that is wrong
     * by one every midnight.
     */
    @Transactional(readOnly = true)
    public List<RoomRow> myRooms() {
        Long shelterId = requireMyShelter();
        return jdbc.query("""
                SELECT  su.id, su.name, su.species, su.is_active, su.capacity,
                        b.status       AS boarding_status,
                        b.start_date, b.end_date,
                        DATEDIFF(CURDATE(), b.start_date) AS nights_so_far,
                        bp.id          AS b_pet_id,
                        bp.name        AS b_pet_name,
                        bo.full_name   AS b_owner_name,
                        a.id           AS visit_id,
                        a.slot         AS visit_slot,
                        dp.id          AS d_pet_id,
                        dp.name        AS d_pet_name,
                        downer.full_name AS d_owner_name,

                        -- HOW FULL THE ROOM IS. The two kinds of occupant are
                        -- counted separately and added, because they live in
                        -- different tables and there is no row anywhere that
                        -- represents "an animal in a room" in general.
                        --
                        -- These are the SAME two counts BoardingService.accept
                        -- and DaycareService.checkIn take under a lock before
                        -- admitting an animal. This one is unlocked on purpose
                        -- -- it draws a screen, and a room that fills between
                        -- the render and the click is caught where it matters.
                        ((SELECT COUNT(*)
                            FROM boarding_requests b2
                           WHERE b2.suite_id = su.id
                             AND b2.status IN ('ACCEPTED','ACTIVE')
                             AND CURDATE() BETWEEN b2.start_date AND b2.end_date)
                       + (SELECT COUNT(*)
                            FROM daycare_attendance a2
                           WHERE a2.suite_id        = su.id
                             AND a2.visit_date      = CURDATE()
                             AND a2.checked_out_at IS NULL)) AS occupied

                FROM        shelter_suites su

                -- THE FACE OF THE ROOM, NOT ALL OF IT. Pinned to one id by a
                -- correlated subquery rather than joined on a predicate: a
                -- predicate join would return one row per occupant and
                -- duplicate the room, which is exactly what stopped being
                -- impossible when V16 made capacity a number.
                --
                -- Earliest arrival wins the line, on the same reasoning as
                -- whoIsHere ordering longest-first: the animal that has been
                -- here longest is the one somebody needs to think about. Ties
                -- break on id so the answer is stable between refreshes.
                --
                -- ACCEPTED and ACTIVE are exactly the two statuses
                -- BoardingStatus.holdsDates() names.
                LEFT JOIN   boarding_requests b
                        ON  b.id = (SELECT b3.id
                                      FROM boarding_requests b3
                                     WHERE b3.suite_id = su.id
                                       AND b3.status IN ('ACCEPTED','ACTIVE')
                                       AND CURDATE() BETWEEN b3.start_date
                                                         AND b3.end_date
                                     ORDER BY b3.start_date, b3.id
                                     LIMIT 1)
                LEFT JOIN   pets  bp ON bp.id = b.pet_id
                LEFT JOIN   users bo ON bo.id = b.owner_id

                -- The day occupant, chosen the same way: here today, not yet
                -- gone home, earliest through the door.
                LEFT JOIN   daycare_attendance a
                        ON  a.id = (SELECT a3.id
                                      FROM daycare_attendance a3
                                     WHERE a3.suite_id        = su.id
                                       AND a3.visit_date      = CURDATE()
                                       AND a3.checked_out_at IS NULL
                                     ORDER BY a3.checked_in_at, a3.id
                                     LIMIT 1)
                LEFT JOIN   daycare_subscriptions ds ON ds.id = a.subscription_id
                LEFT JOIN   pets  dp     ON dp.id     = ds.pet_id
                LEFT JOIN   users downer ON downer.id = ds.owner_id
                WHERE   su.shelter_id = ?
                ORDER BY su.is_active DESC, su.name
                """, (rs, i) -> {
            String boardingStatus = rs.getString("boarding_status");
            boolean hasVisit = rs.getObject("visit_id") != null;
            String state = boardingStatus == null
                    ? (hasVisit ? "DAYCARE" : "FREE")
                    : ("ACTIVE".equals(boardingStatus) ? "OCCUPIED" : "RESERVED");
            boolean boarding = boardingStatus != null;
            String slot = rs.getString("visit_slot");
            return new RoomRow(
                    rs.getLong("id"),
                    rs.getString("name"),
                    rs.getString("species") == null ? null
                            : SuiteSpecies.valueOf(rs.getString("species")),
                    rs.getBoolean("is_active"),
                    state,
                    boarding
                        ? (rs.getObject("b_pet_id") == null ? null : rs.getLong("b_pet_id"))
                        : (rs.getObject("d_pet_id") == null ? null : rs.getLong("d_pet_id")),
                    boarding ? rs.getString("b_pet_name")   : rs.getString("d_pet_name"),
                    boarding ? rs.getString("b_owner_name") : rs.getString("d_owner_name"),
                    boarding && rs.getDate("start_date") != null
                            ? rs.getDate("start_date").toLocalDate() : null,
                    boarding && rs.getDate("end_date") != null
                            ? rs.getDate("end_date").toLocalDate() : null,
                    slot == null ? null : DaycareSlot.valueOf(slot).label(),
                    // Only meaningful once the animal has actually arrived.
                    "ACTIVE".equals(boardingStatus) ? rs.getInt("nights_so_far") : null,
                    rs.getInt("occupied"),
                    rs.getInt("capacity"));
        }, shelterId);
    }

    /**
     * Every animal on the premises, and how long it has been here.
     *
     * A UNION OF THREE, because "who is here" has three answers that live in
     * three tables and no column anywhere says which kind a row is -- the
     * `kind` literal in each arm is what tells them apart.
     *
     *   RESIDENT  one of ours, waiting for a home. Days since intake.
     *   BOARDING  somebody's pet, checked in. Nights since the stay started.
     *   DAYCARE   here for one band today. Always 0 days, by definition.
     *
     * DATEDIFF, NOT A STORED COUNTER. The one thing a "days here" column is
     * guaranteed to be is wrong tomorrow.
     *
     * ORDERED LONGEST FIRST, because that is the only reason anybody opens
     * this list: the animal that has been waiting longest is the one that
     * needs something doing about it.
     */
    @Transactional(readOnly = true)
    public List<OnSiteRow> whoIsHere() {
        Long shelterId = requireMyShelter();
        return jdbc.query("""
                SELECT 'RESIDENT' AS kind, p.id AS pet_id, p.name AS pet_name,
                       p.species, NULL AS owner_name, NULL AS room_name,
                       DATE(p.created_at) AS since,
                       DATEDIFF(CURDATE(), DATE(p.created_at)) AS days_here
                  FROM pets p
                 WHERE p.shelter_id = ?
                   AND p.status IN ('AVAILABLE','PENDING')

                UNION ALL

                SELECT 'BOARDING', p.id, p.name, p.species, o.full_name, su.name,
                       b.start_date,
                       DATEDIFF(CURDATE(), b.start_date)
                  FROM boarding_requests b
                  JOIN pets  p ON p.id = b.pet_id
                  JOIN users o ON o.id = b.owner_id
                  LEFT JOIN shelter_suites su ON su.id = b.suite_id
                 WHERE b.shelter_id = ?
                   AND b.status = 'ACTIVE'

                UNION ALL

                SELECT 'DAYCARE', p.id, p.name, p.species, o.full_name, su.name,
                       a.visit_date, 0
                  FROM daycare_attendance a
                  JOIN daycare_subscriptions ds ON ds.id = a.subscription_id
                  JOIN pets  p  ON p.id  = ds.pet_id
                  JOIN users o  ON o.id  = ds.owner_id
                  JOIN shelter_suites su ON su.id = a.suite_id
                 WHERE ds.shelter_id     = ?
                   AND a.visit_date      = CURDATE()
                   AND a.checked_out_at IS NULL

                ORDER BY days_here DESC, pet_name
                """, (rs, i) -> new OnSiteRow(
                        rs.getString("kind"),
                        rs.getLong("pet_id"),
                        rs.getString("pet_name"),
                        rs.getString("species"),
                        rs.getString("owner_name"),
                        rs.getString("room_name"),
                        rs.getDate("since") == null ? null : rs.getDate("since").toLocalDate(),
                        rs.getLong("days_here")),
                shelterId, shelterId, shelterId);
    }

    /**
     * The caller's own shelter, or 403. STAFF only by construction: an admin
     * has no shelter_id (ck_users_staff_shelter), and every screen these two
     * feed is built around "my rooms, today".
     */
    private Long requireMyShelter() {
        Long shelterId = scopeGuard.require().shelterId();
        if (shelterId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Your account does not belong to a shelter.");
        }
        scopeGuard.requireShelter(shelterId);
        return shelterId;
    }

    // ------------------------------------------------------------- writing

    /**
     * Upload a shelter's logo.
     *
     * ITS OWN ENDPOINT, like the pet photos and for the same reason: a file
     * cannot ride in a JSON body without base64, which inflates it by a third
     * and has to be held whole in memory at both ends.
     *
     * THE ORDER IS STORE, SAVE, THEN DELETE THE OLD ONE, and it is the only
     * order that fails safely. Deleting first and then failing to save leaves
     * a row pointing at a file that is gone -- a broken image with no way back.
     * This way the worst case is an orphaned file nobody references, which
     * costs disk and nothing else.
     */
    @Transactional
    public ShelterRow setLogo(Long id, org.springframework.web.multipart.MultipartFile file) {
        Shelter shelter = shelterRepository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such shelter."));

        String previous = shelter.getLogoPath();
        shelter.setLogoPath(storage.store(file, "shelters"));
        shelterRepository.save(shelter);

        if (previous != null) storage.delete(previous);
        return get(id);
    }



    @Transactional
    public ShelterRow create(CreateShelterRequest r) {
        String name = r.name().trim();
        requireNameFree(name, null);

        Shelter shelter = Shelter.builder()
                .name(name)
                .email(r.email().trim().toLowerCase())
                .phone(r.phone().trim())
                .addressLine(r.addressLine().trim())
                .city(r.city().trim())
                .state(r.state().trim())
                .zip(r.zip().trim())
                .capacity(r.capacity())
                // A shelter an admin typed in is one they have already vetted,
                // so ACTIVE is the sane default. PENDING exists for a future
                // self-registration flow that does not go through this method.
                .status(r.status() == null ? ShelterStatus.ACTIVE : r.status())
                .description(blankToNull(r.description()))
                .boardingRateDaily(r.boardingRateDaily() == null
                        ? BigDecimal.ZERO : r.boardingRateDaily())
                // V13. Absent means "no daycare", not "free daycare" -- zero is
                // exactly the value DaycareService.offers() filters the shelter
                // out on, so leaving it unset opts out rather than mispricing.
                .daycareMonthlyPrice(r.daycareMonthlyPrice() == null
                        ? BigDecimal.ZERO : r.daycareMonthlyPrice())
                .daycareSlotCapacity(r.daycareSlotCapacity() == null
                        ? 20 : r.daycareSlotCapacity())
                .logoPath(blankToNull(r.logoPath()))
                .build();

        shelter = save(shelter);
        audit.record(AuditAction.SHELTER_ADDED, AuditAction.SHELTER, shelter.getId(),
                shelter.getName() + " joined the network.");

        log.info("Admin created shelter {} ({})", shelter.getId(), shelter.getName());
        return one(shelter.getId());
    }

    @Transactional
    public ShelterRow update(Long id, UpdateShelterRequest r) {
        Shelter shelter = load(id);
        String name = r.name().trim();
        requireNameFree(name, id);

        // CAPACITY MAY BE REDUCED BELOW THE CURRENT OCCUPANCY, and that is
        // allowed on purpose. A shelter that loses a room still has the
        // animals it already has; refusing the edit would not move them, it
        // would only stop the record matching reality. The admin table shows
        // the bar over 100% until the numbers come back in line.
        shelter.setName(name);
        shelter.setEmail(r.email().trim().toLowerCase());
        shelter.setPhone(r.phone().trim());
        shelter.setAddressLine(r.addressLine().trim());
        shelter.setCity(r.city().trim());
        shelter.setState(r.state().trim());
        shelter.setZip(r.zip().trim());
        shelter.setCapacity(r.capacity());
        shelter.setDescription(blankToNull(r.description()));
        if (r.boardingRateDaily() != null) {
            shelter.setBoardingRateDaily(r.boardingRateDaily());
        }
        // Null means "not sent", not "set to zero" -- the same rule the line
        // above follows. A form that omits these must not silently close a
        // shelter's daycare programme.
        if (r.daycareMonthlyPrice() != null) {
            shelter.setDaycareMonthlyPrice(r.daycareMonthlyPrice());
        }
        if (r.daycareSlotCapacity() != null) {
            shelter.setDaycareSlotCapacity(r.daycareSlotCapacity());
        }
        shelter.setLogoPath(blankToNull(r.logoPath()));

        save(shelter);
        log.info("Admin updated shelter {}", id);
        return one(id);
    }

    /**
     * Change a shelter's status.
     *
     * Deactivating does NOT touch its pets or its staff accounts. That is a
     * deliberate refusal to be clever: cascading a status change would
     * deactivate real people's logins as a side effect of an edit to a
     * different table, and there would be no way to tell afterwards which
     * accounts were switched off by hand and which by the cascade. An admin
     * who wants those staff disabled disables them on the Users page, where
     * the action says what it does.
     */
    @Transactional
    public ShelterRow setStatus(Long id, ShelterStatus status) {
        Shelter shelter = load(id);

        if (status == ShelterStatus.INACTIVE) {
            long staff = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM users
                     WHERE shelter_id = ? AND role = 'STAFF' AND is_active = TRUE
                    """, Long.class, id);
            long pets = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM pets
                     WHERE shelter_id = ? AND status <> 'ADOPTED'
                    """, Long.class, id);
            // Not a refusal -- a warning in the log, because this is the
            // moment somebody will later ask "why can nobody at Haven Woods
            // list a pet any more".
            if (staff > 0 || pets > 0) {
                log.warn("Shelter {} deactivated while it still has {} active "
                         + "staff and {} unadopted pets", id, staff, pets);
            }
        }

        shelter.setStatus(status);
        shelterRepository.save(shelter);
        log.info("Admin set shelter {} status={}", id, status);
        return one(id);
    }

    // -------------------------------------------------------------- suites

    /**
     * A shelter's boarding rooms.
     *
     * Readable by an admin, or by staff of that shelter. requireShelter is
     * what makes the second half true: without it, any staff member could
     * read the room list of any shelter in the network by changing the id in
     * the URL, and hasRole('STAFF') would happily let them.
     */
    @Transactional(readOnly = true)
    public List<SuiteRow> suites(Long shelterId) {
        load(shelterId);
        scopeGuard.requireShelter(shelterId);
        return suiteRepository.findByShelterIdOrderByNameAsc(shelterId).stream()
                .map(ShelterService::suiteRow)
                .toList();
    }

    @Transactional
    public SuiteRow addSuite(Long shelterId, SuiteRequest r) {
        load(shelterId);
        scopeGuard.requireShelter(shelterId);

        String name = r.name().trim();
        suiteRepository.findByShelterIdAndNameIgnoreCase(shelterId, name)
                .ifPresent(existing -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "This shelter already has a suite called \"" + name + "\".");
                });

        ShelterSuite suite = ShelterSuite.builder()
                .shelterId(shelterId)
                .name(name)
                .species(r.species())              // null = takes any species
                .active(r.active() == null || r.active())
                // V16. Null means the caller did not say, so take the column
                // default of 1 rather than guessing a room size. @Min(1) on
                // the request has already refused zero.
                .capacity(r.capacity() == null ? 1 : r.capacity())
                .build();

        try {
            suite = suiteRepository.save(suite);
        } catch (DataIntegrityViolationException e) {
            // uq_suite_per_shelter. The lookup above lost a race with another
            // admin; the constraint caught it, which is the point of having it.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This shelter already has a suite called \"" + name + "\".");
        }
        log.info("Suite {} added to shelter {}", suite.getId(), shelterId);
        return suiteRow(suite);
    }

    @Transactional
    public SuiteRow updateSuite(Long shelterId, Long suiteId, SuiteRequest r) {
        ShelterSuite suite = loadSuite(shelterId, suiteId);
        scopeGuard.requireShelter(shelterId);

        String name = r.name().trim();
        suiteRepository.findByShelterIdAndNameIgnoreCase(shelterId, name)
                .filter(other -> !other.getId().equals(suiteId))
                .ifPresent(other -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "This shelter already has a suite called \"" + name + "\".");
                });

        suite.setName(name);
        suite.setSpecies(r.species());
        if (r.active() != null) {
            suite.setActive(r.active());
        }
        // V16. NULL MEANS "LEAVE IT", NOT "ONE". A rename posted from a form
        // that predates the capacity field must not silently shrink a
        // twenty-place cat loft back to a single kennel -- so an absent value
        // keeps what is there, exactly as `active` above already does.
        //
        // NOT VALIDATED AGAINST CURRENT OCCUPANCY, and that is deliberate. A
        // shelter that closes half a room needs to be able to say so while the
        // animals are still in it; refusing the edit would mean the record
        // could never catch up with the building. The capacity check happens
        // where the next animal is admitted, which is the moment it matters.
        if (r.capacity() != null) {
            suite.setCapacity(r.capacity());
        }
        suiteRepository.save(suite);
        return suiteRow(suite);
    }

    /**
     * Take a suite out of service.
     *
     * Deactivation, not deletion, and for a harder reason than the shelter
     * case: boarding_requests.suite_id points here, so a past stay records
     * which room the animal was in. Deleting the row would either be refused
     * by the foreign key or would erase that history.
     */
    @Transactional
    public SuiteRow deactivateSuite(Long shelterId, Long suiteId) {
        ShelterSuite suite = loadSuite(shelterId, suiteId);
        scopeGuard.requireShelter(shelterId);
        suite.setActive(false);
        suiteRepository.save(suite);
        log.info("Suite {} deactivated", suiteId);
        return suiteRow(suite);
    }

    // ------------------------------------------------------------- helpers

    private Shelter load(Long id) {
        return shelterRepository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such shelter."));
    }

    /**
     * Loads a suite AND checks it belongs to the shelter in the path.
     *
     * The two-argument form matters. Looking the suite up by id alone would
     * let /admin/shelters/1/suites/99 edit shelter 2's suite -- the id exists,
     * the caller has the role, and the mismatch is invisible.
     */
    private ShelterSuite loadSuite(Long shelterId, Long suiteId) {
        ShelterSuite suite = suiteRepository.findById(suiteId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such suite."));
        if (!suite.getShelterId().equals(shelterId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No such suite at this shelter.");
        }
        return suite;
    }

    private Shelter save(Shelter shelter) {
        try {
            return shelterRepository.save(shelter);
        } catch (DataIntegrityViolationException e) {
            // uq_shelters_name, or one of the CHECK constraints. The service
            // validates first, so reaching here means a race or a rule the
            // DTO does not cover -- either way the caller gets a sentence.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That shelter could not be saved. Check the name is not "
                    + "already taken and the capacity is above zero.");
        }
    }

    private void requireNameFree(String name, Long allowedId) {
        shelterRepository.findByNameIgnoreCase(name)
                .filter(other -> allowedId == null || !other.getId().equals(allowedId))
                .ifPresent(other -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "A shelter called \"" + name + "\" already exists.");
                });
    }

    private ShelterRow one(Long id) {
        List<ShelterRow> rows = jdbc.query("""
            SELECT  s.*,
                    (SELECT COUNT(*) FROM pets p
                      WHERE p.shelter_id = s.id
                        AND p.status <> 'ADOPTED')            AS pet_count,
                    (SELECT COUNT(*) FROM users u
                      WHERE u.shelter_id = s.id
                        AND u.role = 'STAFF'
                        AND u.is_active = TRUE)               AS staff_count,
                    (SELECT COUNT(*) FROM shelter_suites sc
                      WHERE sc.shelter_id = s.id)             AS suite_count,
                    -- PLACES, NOT ROOMS. suite_count is how many rooms exist;
                    -- this is how many animals they hold between them, which
                    -- is the number daycare_slot_capacity has to be set
                    -- against. Active rooms only: a retired one is not
                    -- somewhere anybody can be put.
                    (SELECT COALESCE(SUM(sp.capacity), 0) FROM shelter_suites sp
                      WHERE sp.shelter_id = s.id
                        AND sp.is_active)                     AS suite_places,
                    -- ANIMALS ACTUALLY IN THE BUILDING. Three sources, and
                    -- they do not overlap: a resident is one of ours awaiting
                    -- a home, a boarder is somebody's pet that has checked in,
                    -- a daycare pet is here for one band today.
                    --
                    -- This is NOT pet_count above. That one is the shelter's
                    -- books; both were rendered under the label "Pets in Care",
                    -- which is why accepting a boarding request appeared to
                    -- change nothing -- a boarded pet has no shelter_id and was
                    -- never on those books.
                    ((SELECT COUNT(*) FROM pets p2
                       WHERE p2.shelter_id = s.id
                         AND p2.status IN ('AVAILABLE','PENDING'))
                   + (SELECT COUNT(*) FROM boarding_requests b2
                       WHERE b2.shelter_id = s.id
                         AND b2.status = 'ACTIVE')
                   + (SELECT COUNT(*) FROM daycare_attendance a2
                       JOIN daycare_subscriptions ds2 ON ds2.id = a2.subscription_id
                       WHERE ds2.shelter_id     = s.id
                         AND a2.visit_date      = CURDATE()
                         AND a2.checked_out_at IS NULL)) AS on_site_count
            FROM   shelters s
            WHERE  s.id = ?
            """, this::mapRow, id);

        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such shelter.");
        }
        return rows.get(0);
    }

    private ShelterRow mapRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        String city = rs.getString("city");
        String state = rs.getString("state");
        return new ShelterRow(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getString("address_line"),
                city,
                state,
                rs.getString("zip"),
                city + ", " + state,
                rs.getInt("capacity"),
                rs.getLong("pet_count"),
                rs.getLong("staff_count"),
                rs.getLong("suite_count"),
                rs.getInt("suite_places"),
                ShelterStatus.valueOf(rs.getString("status")),
                rs.getString("description"),
                rs.getBigDecimal("boarding_rate_daily"),
                rs.getBigDecimal("daycare_monthly_price"),
                rs.getInt("daycare_slot_capacity"),
                rs.getString("logo_path"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getLong("on_site_count"));
    }

    private static SuiteRow suiteRow(ShelterSuite s) {
        return new SuiteRow(s.getId(), s.getShelterId(), s.getName(),
                s.getSpecies(), s.isActive(), s.getCapacity());
    }

    /** "" and "   " are how an empty form field arrives; the column means NULL. */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
