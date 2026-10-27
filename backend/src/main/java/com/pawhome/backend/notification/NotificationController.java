package com.pawhome.backend.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import static com.pawhome.backend.notification.NotificationDtos.*;

/**
 * The bell, for every role.
 *
 * NO ROLE CHECK BEYOND "SIGNED IN", and no id in any path that names a user:
 * every method here reads the caller from the security context, so there is no
 * URL that could be edited to read somebody else's inbox.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService service;

    @GetMapping
    public Inbox inbox() {
        return service.inbox();
    }

    @PostMapping("/read")
    public Inbox markAllRead() {
        return service.markAllRead();
    }

    @PostMapping("/{id}/read")
    public Inbox markRead(@PathVariable Long id) {
        return service.markRead(id);
    }
}
