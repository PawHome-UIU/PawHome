package com.pawhome.backend.application;

import com.pawhome.backend.pet.Pet;
import com.pawhome.backend.pet.PetRepository;
import com.pawhome.backend.pet.PetStatus;
import com.pawhome.backend.quiz.QuizAttempt;
import com.pawhome.backend.quiz.QuizAttemptRepository;
import com.pawhome.backend.quiz.QuizType;
import com.pawhome.backend.notification.NotificationService;
import com.pawhome.backend.notification.NotificationType;
import com.pawhome.backend.audit.AuditAction;
import com.pawhome.backend.audit.AuditService;
import com.pawhome.backend.security.ScopeGuard;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static com.pawhome.backend.application.ApplicationDtos.*;

/**
 * The adoption pipeline.
 *
 * APPROVING IS THE ONLY PLACE A PET GETS AN OWNER, and that is not a
 * convention -- it is what ck_pets_owner_when_placed forces:
 *
 *     (status IN ('ADOPTED','BOARDING') AND owner_id IS NOT NULL) OR
 *     (status NOT IN (...)             AND owner_id IS     NULL)
 *
 * PetService refuses to set ADOPTED for exactly this reason: the owner is a
 * decision, not a dropdown. approve() is where that decision is recorded, and
 * it does four things that must all happen or none of them:
 *
 *   1. the application becomes APPROVED, with who decided and when;
 *   2. an adoptions row is written -- the permanent record;
 *   3. the pet becomes ADOPTED and gains its owner;
 *   4. every OTHER open application for that pet is refused as
 *      PET_ALREADY_MATCHED.
 *
 * Step 4 is the one worth arguing for. Without it the queue keeps showing
 * applications for an animal that has gone home, and the people who wrote them
 * are left waiting on an answer that will never come. The schema anticipated
 * this: PET_ALREADY_MATCHED is one of the six decline codes.
 *
 * ONE @Transactional COVERS ALL FOUR. Half of this -- an adopted pet with no
 * adoption record, or an approved application whose pet is still available --
 * is worse than none of it.
 *
 * AND ALL FOUR STATEMENTS ARE WRITTEN DOWN. The three UPDATEs live in
 * ApplicationRepository and PetRepository as @Modifying @Query -- readable in
 * the source, naming only the columns that change, rather than assembled by
 * Hibernate from which entity fields happen to be dirty at commit. The one
 * INSERT stays an entity save, because a new row has no "what changed"
 * question to answer. What that buys, besides being readable:
 *
 *   - step 4 is ONE statement instead of one per rival application;
 *   - each UPDATE carries `AND status = 'PENDING'`, so two reviewers clicking
 *     at the same time is settled by the database, not by whoever's flush
 *     happens to land second.
 */
@Service
@RequiredArgsConstructor
public class ApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationService.class);

    private final ApplicationRepository applicationRepository;
    private final AdoptionRepository adoptionRepository;
    private final PetRepository petRepository;
    private final QuizAttemptRepository attemptRepository;
    private final JdbcTemplate jdbc;
    private final NotificationService notifications;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;

    // ------------------------------------------------------------- applying

    /**
     * Apply for a pet.
     *
     * THE SCORE IS READ FROM THE ATTEMPT, never from the request. An applicant
     * who could send their own quiz_score would be marking their own
     * eligibility, and the column is what a reviewer sees first.
     */
    @Transactional
    public ApplicationRow apply(ApplyRequest r) {
        Long me = scopeGuard.require().id();

        Pet pet = petRepository.findById(r.petId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such pet."));

        if (pet.getStatus() != PetStatus.AVAILABLE) {
            // PENDING is included deliberately: a pet with an application under
            // review is still one animal, and a second applicant should be told
            // now rather than after writing everything out.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    pet.getName() + " is not available for adoption at the moment.");
        }

        QuizAttempt attempt = attemptRepository.findById(r.eligibilityAttemptId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Take the eligibility quiz first."));

        // Both checks matter and they fail differently. A foreign attempt is
        // somebody applying on the strength of another person's answers; the
        // wrong type would be caught by fk_apps_attempt, but as a constraint
        // violation nobody could read.
        if (!attempt.getUserId().equals(me)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "That quiz attempt is not yours.");
        }
        if (attempt.getQuizType() != QuizType.ELIGIBILITY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "An adoption application needs an eligibility attempt, "
                    + "not a " + attempt.getQuizType().name().toLowerCase() + " one.");
        }

        // uq_one_open_app would catch this, as a duplicate-key error. Checking
        // first is what turns it into a sentence -- and the message has to say
        // that re-applying after a refusal IS allowed, because the index
        // deliberately permits it.
        if (applicationRepository.existsByApplicantIdAndPetIdAndStatus(
                me, pet.getId(), ApplicationStatus.PENDING)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "You already have an application for " + pet.getName()
                    + " waiting for a decision.");
        }

        AdoptionApplication saved = applicationRepository.save(AdoptionApplication.builder()
                .petId(pet.getId())
                .applicantId(me)
                .eligibilityAttemptId(attempt.getId())
                .quizScore((short) (attempt.getScore() == null ? 0 : attempt.getScore()))
                .applicantName(r.applicantName().trim())
                .applicantEmail(r.applicantEmail().trim().toLowerCase())
                .applicantPhone(r.applicantPhone().trim())
                .applicantAddress(r.applicantAddress().trim())
                .applicantCity(r.applicantCity().trim())
                .homeType(r.homeType().trim())
                .timeAtHome(r.timeAtHome().trim())
                .petExperience(r.petExperience().trim())
                .householdNote(blankToNull(r.householdNote()))
                .status(ApplicationStatus.PENDING)
                .build());

        // In this transaction on purpose: an application nobody is told about
        // is an application that sits in a queue nobody opens.
        notifications.raiseForShelterStaff(pet.getShelterId(),
                NotificationType.APPLICATION_SUBMITTED,
                "New application for " + pet.getName(),
                r.applicantName().trim() + " has applied to adopt " + pet.getName() + ".",
                "/staff/applications/" + saved.getId());

        log.info("User {} applied for pet {} as application {}", me, pet.getId(), saved.getId());
        return row(saved.getId());
    }

    /** The caller's own applications, newest first. */
    @Transactional(readOnly = true)
    public List<ApplicationRow> mine() {
        return jdbc.query(SELECT_ROW + " WHERE a.applicant_id = ? ORDER BY a.submitted_at DESC",
                ROW_MAPPER, scopeGuard.require().id());
    }

    /** Take an application back, before anyone has decided. */
    @Transactional
    public ApplicationRow withdraw(Long id) {
        AdoptionApplication app = load(id);
        scopeGuard.requireOwner(app.getApplicantId());

        if (app.getStatus() != ApplicationStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That application has already been decided.");
        }

        // reviewed_at but deliberately not reviewed_by -- see markWithdrawn.
        // The moment matters: it is the difference between the adopter's page
        // saying when they took it back and saying "an earlier date".
        if (applicationRepository.markWithdrawn(id, LocalDateTime.now()) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That application has already been decided.");
        }
        return row(id);
    }

    // ------------------------------------------------------------ reviewing

    /** The shelter's queue. Scoped through the PET, which is what has a shelter. */
    @Transactional(readOnly = true)
    public List<ApplicationRow> forShelter() {
        Long scope = scopeGuard.shelterScope();
        return jdbc.query(SELECT_ROW + """
                 WHERE (? IS NULL OR p.shelter_id = ?)
                 ORDER BY FIELD(a.status,'PENDING','APPROVED','REJECTED','WITHDRAWN'),
                          a.submitted_at
                """, ROW_MAPPER, scope, scope);
    }

    @Transactional(readOnly = true)
    public ApplicationRow get(Long id) {
        ApplicationRow found = row(id);
        // The application has no shelter of its own; the pet does.
        scopeGuard.requireShelter(found.shelterId());
        return found;
    }

    @Transactional(readOnly = true)
    public ApplicationCounts counts() {
        Long scope = scopeGuard.shelterScope();
        return jdbc.queryForObject("""
            SELECT  COUNT(*)                     AS total,
                    SUM(a.status = 'PENDING')    AS pending,
                    SUM(a.status = 'APPROVED')   AS approved,
                    SUM(a.status = 'REJECTED')   AS rejected,
                    SUM(a.status = 'WITHDRAWN')  AS withdrawn
            FROM       adoption_applications a
            JOIN       pets p ON p.id = a.pet_id
            WHERE      (? IS NULL OR p.shelter_id = ?)
            """, (rs, i) -> new ApplicationCounts(rs.getLong("total"), rs.getLong("pending"),
                    rs.getLong("approved"), rs.getLong("rejected"), rs.getLong("withdrawn")),
                scope, scope);
    }

    /** See the class comment: four writes, one transaction. */
    @Transactional
    public ApplicationRow approve(Long id, ApproveRequest r) {
        AdoptionApplication app = load(id);
        Pet pet = petRepository.findById(app.getPetId()).orElseThrow();
        scopeGuard.requireShelter(pet.getShelterId());

        if (app.getStatus() != ApplicationStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That application has already been " + app.getStatus().name().toLowerCase() + ".");
        }
        if (pet.getStatus() != PetStatus.AVAILABLE && pet.getStatus() != PetStatus.PENDING) {
            // Reachable when the pet was placed by another application between
            // this page loading and the click.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    pet.getName() + " is " + pet.getStatus().name().toLowerCase()
                    + " and cannot be adopted again.");
        }

        LocalDate adoptedOn = r.adoptedOn() == null ? LocalDate.now() : r.adoptedOn();
        if (adoptedOn.isAfter(LocalDate.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "An adoption cannot be dated in the future.");
        }

        LocalDateTime now = LocalDateTime.now();
        Long reviewer = scopeGuard.require().id();

        // ck_apps_decline needs a message as well as a code, and this one is
        // read by a person -- so it says what happened, not "closed". Built
        // once here because the row and the notification must say the same
        // thing; sending one sentence and storing another is how a support
        // question becomes unanswerable.
        String closingMessage = pet.getName() + " has been adopted by another "
                + "applicant, so this application has been closed. Please do "
                + "look at the other pets waiting for a home.";

        // --- 1 of 4: the decision itself ---------------------------------
        // The check above already refused a non-PENDING application. This is
        // the same check again, in SQL, where it is atomic: if a second
        // reviewer got here first their UPDATE matched the row and ours
        // matches nothing, and we find out from the row count rather than by
        // overwriting their decision.
        if (applicationRepository.markApproved(id, reviewer, now) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That application was decided by someone else a moment ago.");
        }

        // --- 2 of 4: the adoption record ---------------------------------
        // Still an entity save, and deliberately: this is an INSERT of a brand
        // new row, so there is no "which columns changed" question to answer
        // and nothing is hidden. The builder IS the statement.
        adoptionRepository.save(Adoption.builder()
                .applicationId(app.getId())
                .petId(pet.getId())
                .adopterId(app.getApplicantId())
                .shelterId(pet.getShelterId())
                .adoptedOn(adoptedOn)
                .feePaid(r.feePaid() == null ? pet.getAdoptionFee() : r.feePaid())
                .build());

        // --- 3 of 4: the pet changes hands -------------------------------
        // Status and owner move in the one statement; see markAdopted for why
        // ck_pets_owner_when_placed leaves no choice about that.
        if (petRepository.markAdopted(pet.getId(), app.getApplicantId()) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    pet.getName() + " was placed by another application a moment ago.");
        }

        // --- 4 of 4: everybody else who wanted this pet ------------------
        // Read the rivals BEFORE closing them, because the people are the
        // point: each one gets told. The approved application is already
        // APPROVED by now, so it is not in this list.
        List<AdoptionApplication> rivals =
                applicationRepository.findByPetIdAndStatus(pet.getId(), ApplicationStatus.PENDING);

        int alsoDeclined = rivals.isEmpty() ? 0
                : applicationRepository.closeRivalApplications(
                        pet.getId(), app.getId(), reviewer, now, closingMessage);

        for (AdoptionApplication other : rivals) {
            // The people whose applications this approval closed. They are the
            // ones most likely to be left wondering, so they are told the same
            // sentence the row carries rather than a bare "declined".
            notifications.raise(other.getApplicantId(),
                    NotificationType.APPLICATION_DECIDED,
                    "Your application for " + pet.getName() + " was closed",
                    closingMessage,
                    "/app/applications");
        }

        notifications.raise(app.getApplicantId(),
                NotificationType.APPLICATION_DECIDED,
                pet.getName() + " is yours",
                "Your application was approved. The shelter will be in touch "
                + "about collecting " + pet.getName() + ".",
                "/app/applications");

        // THE AUDIT ROW, INSIDE THE SAME TRANSACTION. An approval that
        // committed without one would be a decision with no recorded author;
        // a row without the approval would record something that did not
        // happen. See AuditService on why neither is allowed.
        audit.record(AuditAction.APPLICATION_APPROVED, AuditAction.APPLICATION, id,
                pet.getName() + " was adopted by " + app.getApplicantName() + ".");

        log.info("Staff {} approved application {} for pet {}; {} other application(s) closed",
                reviewer, id, pet.getId(), alsoDeclined);

        return row(id);
    }

    @Transactional
    public ApplicationRow decline(Long id, DeclineRequest r) {
        AdoptionApplication app = load(id);
        Pet pet = petRepository.findById(app.getPetId()).orElseThrow();
        scopeGuard.requireShelter(pet.getShelterId());

        if (app.getStatus() != ApplicationStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That application has already been " + app.getStatus().name().toLowerCase() + ".");
        }

        if (applicationRepository.markDeclined(id, scopeGuard.require().id(),
                LocalDateTime.now(), r.reasonCode(), r.message().trim()) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That application was decided by someone else a moment ago.");
        }

        notifications.raise(app.getApplicantId(),
                NotificationType.APPLICATION_DECIDED,
                "Your application for " + pet.getName() + " was declined",
                // The sentence ck_apps_decline forced the reviewer to write.
                // Sending "declined" and hiding the reason behind a click is
                // how a person ends up with no idea what to change.
                r.message().trim(),
                "/app/applications");

        audit.record(AuditAction.APPLICATION_DECLINED, AuditAction.APPLICATION, id,
                "Application from " + app.getApplicantName() + " was declined ("
                + r.reasonCode().name().toLowerCase().replace('_', ' ') + ").");

        log.info("Staff {} declined application {} ({})",
                scopeGuard.require().id(), id, r.reasonCode());
        return row(id);
    }

    // ------------------------------------------------------------ internals

    private AdoptionApplication load(Long id) {
        return applicationRepository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such application."));
    }

    /** PRIVATE and unscoped -- every caller checks for itself. */
    private ApplicationRow row(Long id) {
        List<ApplicationRow> found = jdbc.query(SELECT_ROW + " WHERE a.id = ?", ROW_MAPPER, id);
        if (found.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such application.");
        }
        return found.get(0);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * Joins, not subqueries, because every one of these is to exactly one row:
     * an application has one pet, one shelter, one attempt, and at most one
     * reviewer. The reviewer join is LEFT because an undecided application has
     * none.
     */
    private static final String SELECT_ROW = """
        SELECT  a.id, a.pet_id, p.name AS pet_name, p.breed AS pet_breed, p.status AS pet_status,
                (SELECT ph.file_path FROM pet_photos ph
                  WHERE ph.pet_id = p.id AND ph.is_cover = TRUE LIMIT 1) AS pet_cover_photo_path,
                p.shelter_id, s.name AS shelter_name,
                a.applicant_id, a.applicant_name, a.applicant_email, a.applicant_phone,
                a.applicant_address, a.applicant_city, a.home_type, a.time_at_home,
                a.pet_experience, a.household_note, a.quiz_score,
                q.max_score AS quiz_max_score, q.passed AS quiz_passed,
                a.status, a.submitted_at, r.full_name AS reviewed_by_name, a.reviewed_at,
                a.decline_reason_code, a.decline_message,
                ad.adopted_on, ad.fee_paid
        FROM        adoption_applications a
        JOIN        pets          p ON p.id = a.pet_id
        JOIN        shelters      s ON s.id = p.shelter_id
        JOIN        quiz_attempts q ON q.id = a.eligibility_attempt_id
        LEFT JOIN   users         r ON r.id = a.reviewed_by
        -- Cannot fan the result out: uq_adoption_application is UNIQUE on
        -- application_id, so this LEFT JOIN matches at most one row.
        LEFT JOIN   adoptions    ad ON ad.application_id = a.id
        """;

    private static final RowMapper<ApplicationRow> ROW_MAPPER = (rs, i) -> ApplicationRow.builder()
            .id(rs.getLong("id"))
            .petId(rs.getLong("pet_id"))
            .petName(rs.getString("pet_name"))
            .petBreed(rs.getString("pet_breed"))
            .petCoverPhotoPath(rs.getString("pet_cover_photo_path"))
            .petStatus(PetStatus.valueOf(rs.getString("pet_status")))
            .shelterId(rs.getLong("shelter_id"))
            .shelterName(rs.getString("shelter_name"))
            .applicantId(rs.getLong("applicant_id"))
            .applicantName(rs.getString("applicant_name"))
            .applicantEmail(rs.getString("applicant_email"))
            .applicantPhone(rs.getString("applicant_phone"))
            .applicantAddress(rs.getString("applicant_address"))
            .applicantCity(rs.getString("applicant_city"))
            .homeType(rs.getString("home_type"))
            .timeAtHome(rs.getString("time_at_home"))
            .petExperience(rs.getString("pet_experience"))
            .householdNote(rs.getString("household_note"))
            .quizScore(rs.getShort("quiz_score"))
            // SMALLINT and nullable (a personality attempt has none), so read
            // it as an object and widen -- a cast to Integer relies on how the
            // driver happens to box SMALLINT today.
            .quizMaxScore(rs.getObject("quiz_max_score") == null ? null
                    : ((Number) rs.getObject("quiz_max_score")).intValue())
            .quizPassed((Boolean) rs.getObject("quiz_passed"))
            .status(ApplicationStatus.valueOf(rs.getString("status")))
            .submittedAt(rs.getTimestamp("submitted_at").toLocalDateTime())
            .reviewedByName(rs.getString("reviewed_by_name"))
            .reviewedAt(rs.getTimestamp("reviewed_at") == null ? null
                    : rs.getTimestamp("reviewed_at").toLocalDateTime())
            .declineReasonCode(rs.getString("decline_reason_code") == null ? null
                    : DeclineReason.valueOf(rs.getString("decline_reason_code")))
            .declineMessage(rs.getString("decline_message"))
            .adoptedOn(rs.getDate("adopted_on") == null ? null
                    : rs.getDate("adopted_on").toLocalDate())
            .feePaid(rs.getBigDecimal("fee_paid"))
            .build();
}
