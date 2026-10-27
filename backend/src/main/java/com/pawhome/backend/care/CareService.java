package com.pawhome.backend.care;

import com.pawhome.backend.pet.Pet;
import com.pawhome.backend.pet.PetRepository;
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

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static com.pawhome.backend.care.CareDtos.*;

/**
 * Module 6: the rota, and the record of what was done.
 *
 * OVERDUE IS A QUERY. V1 says so at the column and this service never wavers
 * from it: `status = 'PENDING' AND due_at < NOW()`, evaluated when somebody
 * asks. There is no OVERDUE status, no stored flag and no job whose purpose is
 * to keep a boolean true -- because such a boolean is wrong for exactly as long
 * as the job is late, and it is late precisely when the system is busy.
 *
 * ck_tasks_done IS WHY complete() SETS THREE THINGS AT ONCE. The database
 * refuses a DONE row without completed_by and completed_at, which is the
 * constraint refusing to store a tick with nobody and no time behind it. The
 * service writes all three together rather than trusting a caller to.
 *
 * LOGGING AGAINST A TASK CLOSES IT, in the same transaction. A log that said
 * the dog was fed while the feeding task stayed PENDING would make the rota
 * and the record disagree, and the rota is what the next shift reads.
 */
@Service
@RequiredArgsConstructor
public class CareService {

    private static final Logger log = LoggerFactory.getLogger(CareService.class);

    private final CareTaskRepository taskRepository;
    private final CareLogRepository logRepository;
    private final PetRepository petRepository;
    private final JdbcTemplate jdbc;
    private final ScopeGuard scopeGuard;

    // -------------------------------------------------------------- reading

    /** Everything one screen needs, in three queries rather than three calls. */
    @Transactional(readOnly = true)
    public CareBoard board() {
        return new CareBoard(counts(), tasks(), logs());
    }

    @Transactional(readOnly = true)
    public List<CareTaskRow> tasks() {
        Long scope = scopeGuard.shelterScope();
        return jdbc.query(TASK_SQL + """
                 WHERE (? IS NULL OR p.shelter_id = ?)
                 ORDER BY FIELD(t.status,'PENDING','SKIPPED','DONE'),
                          t.priority = 'CRITICAL' DESC,
                          t.due_at
                """, TASK_MAPPER, scope, scope);
    }

    @Transactional(readOnly = true)
    public List<CareLogRow> logs() {
        Long scope = scopeGuard.shelterScope();
        return jdbc.query(LOG_SQL + """
                 WHERE (? IS NULL OR p.shelter_id = ?)
                 ORDER BY l.logged_at DESC, l.id DESC
                """, LOG_MAPPER, scope, scope);
    }

    /**
     * Who a task may be assigned to.
     *
     * The caller's own shelter only, and the same predicate requireColleague
     * enforces on the way in -- a picker offering somebody the server would
     * then refuse is a form that fails after the person filled it in.
     */
    @Transactional(readOnly = true)
    public List<ColleagueRow> colleagues() {
        Long scope = scopeGuard.shelterScope();
        return jdbc.query("""
                SELECT id, full_name FROM users
                 WHERE role = 'STAFF' AND is_active = TRUE AND approval_status = 'APPROVED'
                   AND (? IS NULL OR shelter_id = ?)
                 ORDER BY full_name
                """,
                (rs, i) -> new ColleagueRow(rs.getLong("id"), rs.getString("full_name")),
                scope, scope);
    }

    @Transactional(readOnly = true)
    public CareCounts counts() {
        Long scope = scopeGuard.shelterScope();
        return jdbc.queryForObject("""
                SELECT SUM(t.status = 'PENDING')                             AS pending,
                       SUM(t.status = 'PENDING' AND t.due_at < NOW())        AS overdue,
                       SUM(t.status = 'PENDING' AND t.priority = 'CRITICAL') AS critical,
                       SUM(t.status = 'DONE'
                           AND DATE(t.completed_at) = CURDATE())             AS done_today
                  FROM care_tasks t
                  JOIN pets p ON p.id = t.pet_id
                 WHERE (? IS NULL OR p.shelter_id = ?)
                """,
                (rs, i) -> new CareCounts(rs.getLong("pending"), rs.getLong("overdue"),
                        rs.getLong("critical"), rs.getLong("done_today")),
                scope, scope);
    }

    // -------------------------------------------------------------- writing

    @Transactional
    public CareBoard create(CareTaskRequest r) {
        Pet pet = pet(r.petId());
        scopeGuard.requireShelter(pet.getShelterId());
        requireColleague(r.assignedTo(), pet.getShelterId());

        taskRepository.save(CareTask.builder()
                .petId(pet.getId())
                // Linked to the stay when the animal is in the building for
                // one, so the boarding record carries the care it was given.
                .boardingId(openBoardingFor(pet.getId()))
                .taskType(r.taskType())
                .description(r.description().trim())
                .dueAt(r.dueAt())
                .priority(r.priority())
                .status(CareTaskStatus.PENDING)
                .assignedTo(r.assignedTo())
                .build());

        log.info("Staff {} scheduled a {} task for pet {}",
                scopeGuard.require().id(), r.taskType(), pet.getId());
        return board();
    }

    @Transactional
    public CareBoard update(Long id, CareTaskRequest r) {
        CareTask task = load(id);
        scopeGuard.requireShelter(pet(task.getPetId()).getShelterId());
        requireColleague(r.assignedTo(), pet(task.getPetId()).getShelterId());
        requireOpen(task, "changed");

        // A moved deadline is a NEW deadline, so the reminder is owed again --
        // see V12. Without this the assignee is told once, ever, and a task
        // pushed to next week goes quietly past unmentioned.
        if (!task.getDueAt().equals(r.dueAt())) task.setDueNotifiedAt(null);

        task.setTaskType(r.taskType());
        task.setDescription(r.description().trim());
        task.setDueAt(r.dueAt());
        task.setPriority(r.priority());
        task.setAssignedTo(r.assignedTo());
        taskRepository.save(task);
        return board();
    }

    /**
     * Mark it done.
     *
     * THREE FIELDS OR NONE: ck_tasks_done refuses a DONE row missing either
     * completed_by or completed_at, which is the database declining to store a
     * tick that nobody put there at no particular time.
     */
    @Transactional
    public CareBoard complete(Long id) {
        CareTask task = load(id);
        scopeGuard.requireShelter(pet(task.getPetId()).getShelterId());
        requireOpen(task, "completed");

        task.setStatus(CareTaskStatus.DONE);
        task.setCompletedBy(scopeGuard.require().id());
        task.setCompletedAt(LocalDateTime.now(ZoneOffset.UTC));
        taskRepository.save(task);
        return board();
    }

    /**
     * Mark it skipped.
     *
     * NOT THE SAME AS DONE, and kept apart for the reason DECLINED and
     * CANCELLED are kept apart on a boarding request: a task nobody did is a
     * fact about that day, and folding it into DONE would make "was the dog
     * fed" unanswerable from the record.
     */
    @Transactional
    public CareBoard skip(Long id) {
        CareTask task = load(id);
        scopeGuard.requireShelter(pet(task.getPetId()).getShelterId());
        requireOpen(task, "skipped");

        // completed_by stays NULL: ck_tasks_done only constrains DONE, and
        // naming somebody as having completed a task they skipped would be a
        // lie the constraint happens not to catch.
        task.setStatus(CareTaskStatus.SKIPPED);
        taskRepository.save(task);
        return board();
    }

    @Transactional
    public CareBoard delete(Long id) {
        CareTask task = load(id);
        scopeGuard.requireShelter(pet(task.getPetId()).getShelterId());

        // fk_logs_task is ON DELETE SET NULL, so any log written against this
        // task survives as an ad-hoc entry. That is the schema deciding that
        // the record of what happened outlives the plan that asked for it.
        taskRepository.delete(task);
        log.warn("Staff {} deleted care task {}", scopeGuard.require().id(), id);
        return board();
    }

    /** Record what was done, and close the task it answers if there is one. */
    @Transactional
    public CareBoard logCare(CareLogRequest r) {
        Pet pet = pet(r.petId());
        scopeGuard.requireShelter(pet.getShelterId());
        Long me = scopeGuard.require().id();

        Long taskId = null;
        if (r.taskId() != null) {
            CareTask task = load(r.taskId());
            if (!task.getPetId().equals(pet.getId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "That task belongs to a different pet.");
            }
            taskId = task.getId();
            if (task.getStatus() == CareTaskStatus.PENDING) {
                task.setStatus(CareTaskStatus.DONE);
                task.setCompletedBy(me);
                task.setCompletedAt(LocalDateTime.now(ZoneOffset.UTC));
                taskRepository.save(task);
            }
        }

        logRepository.save(CareLog.builder()
                .petId(pet.getId())
                .boardingId(openBoardingFor(pet.getId()))
                .taskId(taskId)
                .taskType(r.taskType())
                .notes(r.notes().trim())
                .loggedBy(me)
                .build());

        return board();
    }

    // --------------------------------------------------------------- shared

    /**
     * The stay this pet is in the middle of, if any.
     *
     * ACTIVE only: an accepted stay has not started and a completed one is
     * over, so care given now belongs to neither. Null for a shelter resident,
     * which is exactly what V1's "NULL for a shelter resident" means.
     */
    private Long openBoardingFor(Long petId) {
        List<Long> found = jdbc.queryForList("""
                SELECT id FROM boarding_requests
                 WHERE pet_id = ? AND status = 'ACTIVE'
                 ORDER BY start_date DESC LIMIT 1
                """, Long.class, petId);
        return found.isEmpty() ? null : found.get(0);
    }

    /** An assignee has to be staff at the same shelter, or nobody at all. */
    private void requireColleague(Long assignedTo, Long shelterId) {
        if (assignedTo == null) return;
        Long ok = jdbc.queryForObject("""
                SELECT COUNT(*) FROM users
                 WHERE id = ? AND role = 'STAFF' AND shelter_id = ?
                   AND is_active = TRUE AND approval_status = 'APPROVED'
                """, Long.class, assignedTo, shelterId);
        if (ok == null || ok == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That person does not work at this shelter.");
        }
    }

    private static void requireOpen(CareTask task, String verb) {
        if (task.getStatus() != CareTaskStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A task that is " + task.getStatus().name().toLowerCase()
                    + " cannot be " + verb + ".");
        }
    }

    private CareTask load(Long id) {
        return taskRepository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such care task."));
    }

    private Pet pet(Long petId) {
        return petRepository.findById(petId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such pet."));
    }

    private static final String TASK_SQL = """
        SELECT  t.id, t.pet_id, p.name AS pet_name,
                (SELECT ph.file_path FROM pet_photos ph
                  WHERE ph.pet_id = p.id AND ph.is_cover = TRUE LIMIT 1) AS pet_cover_photo_path,
                t.boarding_id, t.task_type, t.description, t.due_at, t.priority, t.status,
                t.assigned_to, a.full_name AS assigned_to_name,
                c.full_name AS completed_by_name, t.completed_at,
                (t.status = 'PENDING' AND t.due_at < NOW()) AS overdue
        FROM        care_tasks t
        JOIN        pets  p ON p.id = t.pet_id
        LEFT JOIN   users a ON a.id = t.assigned_to
        LEFT JOIN   users c ON c.id = t.completed_by
        """;

    private static final RowMapper<CareTaskRow> TASK_MAPPER = (rs, i) -> new CareTaskRow(
            rs.getLong("id"),
            rs.getLong("pet_id"),
            rs.getString("pet_name"),
            rs.getString("pet_cover_photo_path"),
            rs.getObject("boarding_id") == null ? null : rs.getLong("boarding_id"),
            CareTaskType.valueOf(rs.getString("task_type")),
            rs.getString("description"),
            rs.getTimestamp("due_at").toLocalDateTime(),
            CarePriority.valueOf(rs.getString("priority")),
            CareTaskStatus.valueOf(rs.getString("status")),
            rs.getObject("assigned_to") == null ? null : rs.getLong("assigned_to"),
            rs.getString("assigned_to_name"),
            rs.getString("completed_by_name"),
            rs.getTimestamp("completed_at") == null ? null
                    : rs.getTimestamp("completed_at").toLocalDateTime(),
            rs.getBoolean("overdue"));

    private static final String LOG_SQL = """
        SELECT  l.id, l.pet_id, p.name AS pet_name, l.task_id, l.task_type,
                l.notes, l.logged_at, u.full_name AS logged_by_name
        FROM        care_logs l
        JOIN        pets  p ON p.id = l.pet_id
        JOIN        users u ON u.id = l.logged_by
        """;

    private static final RowMapper<CareLogRow> LOG_MAPPER = (rs, i) -> new CareLogRow(
            rs.getLong("id"),
            rs.getLong("pet_id"),
            rs.getString("pet_name"),
            rs.getObject("task_id") == null ? null : rs.getLong("task_id"),
            CareTaskType.valueOf(rs.getString("task_type")),
            rs.getString("notes"),
            rs.getString("logged_by_name"),
            rs.getTimestamp("logged_at").toLocalDateTime());
}
