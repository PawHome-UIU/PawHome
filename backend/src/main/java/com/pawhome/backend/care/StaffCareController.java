package com.pawhome.backend.care;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import static com.pawhome.backend.care.CareDtos.*;

/**
 * The rota.
 *
 * EVERY METHOD RETURNS THE WHOLE BOARD rather than the row it changed. Ticking
 * a task moves four counts, can promote the next overdue one and adds a log
 * line -- so a page that patched one row into its own state would be wrong in
 * three places until the next refresh. One response, one source of truth, at
 * the cost of two extra queries on an action a person takes a few times a
 * shift.
 *
 * STAFF ONLY, matching /api/v1/staff/** rather than widening it: an admin has
 * no shelter, so shelterScope() returns null and this board would silently
 * become every shelter's rota in an API written for one.
 */
@RestController
@RequestMapping("/api/v1/staff/care")
@PreAuthorize("hasRole('STAFF')")
@RequiredArgsConstructor
public class StaffCareController {

    private final CareService service;

    @GetMapping
    public CareBoard board() {
        return service.board();
    }

    /** The assign-to picker. */
    @GetMapping("/colleagues")
    public java.util.List<ColleagueRow> colleagues() {
        return service.colleagues();
    }

    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public CareBoard create(@Valid @RequestBody CareTaskRequest request) {
        return service.create(request);
    }

    @PutMapping("/tasks/{id}")
    public CareBoard update(@PathVariable Long id, @Valid @RequestBody CareTaskRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/tasks/{id}/complete")
    public CareBoard complete(@PathVariable Long id) {
        return service.complete(id);
    }

    /** Done and skipped are different answers -- see CareTaskStatus. */
    @PostMapping("/tasks/{id}/skip")
    public CareBoard skip(@PathVariable Long id) {
        return service.skip(id);
    }

    @DeleteMapping("/tasks/{id}")
    public CareBoard delete(@PathVariable Long id) {
        return service.delete(id);
    }

    /**
     * Record care given.
     *
     * With a taskId it closes that task in the same transaction; without one it
     * is an ad-hoc note, which fk_logs_task allows by being nullable.
     */
    @PostMapping("/logs")
    @ResponseStatus(HttpStatus.CREATED)
    public CareBoard logCare(@Valid @RequestBody CareLogRequest request) {
        return service.logCare(request);
    }
}
