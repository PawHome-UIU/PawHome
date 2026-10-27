package com.pawhome.backend.admin;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import static com.pawhome.backend.admin.AdminUserDtos.*;

/**
 * Admin user management.
 *
 * Sits under /api/v1/admin/**, which SecurityConfig already restricts to
 * ADMIN. The @PreAuthorize on the class is deliberately redundant: URL rules
 * live in one file and are easy to reorganise, and a controller that also
 * states its own requirement keeps working if someone moves it. Two locks, one
 * door -- the cheap kind of redundancy.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService service;
    private final InviteService inviteService;

    @GetMapping
    public List<AdminUserRow> list() {
        return service.list();
    }

    @GetMapping("/counts")
    public Map<String, Object> counts() {
        return service.counts();
    }

    /** Approve a pending staff sign-up. */
    @PostMapping("/{id}/approve")
    public AdminUserRow approve(@PathVariable Long id) {
        return service.approve(id);
    }

    /** Turn one down, with a reason they can read. */
    @PostMapping("/{id}/reject")
    public AdminUserRow reject(@PathVariable Long id,
                               @Valid @RequestBody RejectRequest request) {
        return service.reject(id, request.reason());
    }

    @PostMapping("/{id}/activate")
    public AdminUserRow activate(@PathVariable Long id) {
        return service.setActive(id, true);
    }

    @PostMapping("/{id}/deactivate")
    public AdminUserRow deactivate(@PathVariable Long id) {
        return service.setActive(id, false);
    }

    /** Push a fresh confirmation link to somebody who never received one. */
    @PostMapping("/{id}/resend-verification")
    public Map<String, String> resendVerification(@PathVariable Long id) {
        service.resendVerification(id);
        return Map.of("message", "A new confirmation link is on its way.");
    }

    /**
     * Invite a staff member: creates the account and emails a one-time link
     * that sets its password. See InviteService for why it is created APPROVED
     * but not yet email-verified.
     */
    @PostMapping("/invite")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminUserRow invite(@Valid @RequestBody InviteRequest request) {
        return inviteService.invite(request.fullName(), request.email(),
                request.shelterId(), request.jobTitle());
    }

    /** Send the invitation again. Any previous link stops working. */
    @PostMapping("/{id}/resend-invite")
    public Map<String, String> resendInvite(@PathVariable Long id) {
        inviteService.resendInvite(id);
        return Map.of("message", "The invitation has been sent again.");
    }

    /**
     * Move a staff member to another shelter.
     *
     * See AdminUserService.setShelter: this changes what the person can see on
     * their next request, not on their next login.
     */
    @PostMapping("/{id}/shelter")
    public AdminUserRow setShelter(@PathVariable Long id,
                                   @Valid @RequestBody ShelterTransferRequest request) {
        return service.setShelter(id, request.shelterId());
    }

    /**
     * Mark an address confirmed by hand.
     *
     * Bypasses the email check, so it is logged at WARN. Kept because accounts
     * created while mail was switched off have no other way out.
     */
    @PostMapping("/{id}/verify-email")
    public AdminUserRow forceVerify(@PathVariable Long id) {
        return service.forceVerify(id);
    }
}
