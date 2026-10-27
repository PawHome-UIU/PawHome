package com.pawhome.backend.application;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.pawhome.backend.application.ApplicationDtos.*;

/**
 * The review queue.
 *
 * STAFF ONLY, matching the /api/v1/staff/** URL rule rather than widening it --
 * an admin has no shelter, so shelterScope() returns null and this queue would
 * become every shelter's applications in an API written for one. Same argument
 * as StaffPetController, and the same reason it is written down twice.
 *
 * NO DELETE. An application is the record of a decision about a person; it
 * ends as APPROVED, REJECTED or WITHDRAWN and stays.
 */
@RestController
@RequestMapping("/api/v1/staff/applications")
@PreAuthorize("hasRole('STAFF')")
@RequiredArgsConstructor
public class StaffApplicationController {

    private final ApplicationService service;

    @GetMapping
    public List<ApplicationRow> list() {
        return service.forShelter();
    }

    @GetMapping("/counts")
    public ApplicationCounts counts() {
        return service.counts();
    }

    @GetMapping("/{id}")
    public ApplicationRow get(@PathVariable Long id) {
        return service.get(id);
    }

    /**
     * Approve.
     *
     * Writes the adoption, places the pet with its new owner, and closes the
     * other open applications for that animal -- see ApplicationService.
     */
    @PostMapping("/{id}/approve")
    public ApplicationRow approve(@PathVariable Long id,
                                  @RequestBody(required = false) ApproveRequest request) {
        return service.approve(id, request == null
                ? new ApproveRequest(null, null) : request);
    }

    /** Decline. A reason code AND a message, because ck_apps_decline needs both. */
    @PostMapping("/{id}/decline")
    public ApplicationRow decline(@PathVariable Long id,
                                  @Valid @RequestBody DeclineRequest request) {
        return service.decline(id, request);
    }
}
