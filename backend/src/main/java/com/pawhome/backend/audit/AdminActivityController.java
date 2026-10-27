package com.pawhome.backend.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Recent Activity, for the admin dashboard.
 *
 * ADMIN ONLY, AND READ ONLY. There is no endpoint that writes an audit row and
 * there must never be one: every entry is written by the service that performed
 * the action, inside that action's own transaction. An HTTP endpoint that could
 * append to the trail would let somebody record something that did not happen,
 * which is the one thing this table exists to make impossible.
 *
 * WHY ADMIN AND NOT STAFF. The trail spans every shelter -- who approved what,
 * at which shelter, and when. Scoping it per shelter is a different endpoint
 * with a WHERE clause, and offering an unscoped one to staff would hand every
 * shelter a window onto the others.
 */
@RestController
@RequestMapping("/api/v1/admin/activity")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminActivityController {

    private final AuditService service;

    /** The newest entries. Capped at 100 by the service whatever is asked. */
    @GetMapping
    public List<AuditDtos.ActivityRow> recent(@RequestParam(defaultValue = "12") int limit) {
        return service.recent(limit);
    }
}
