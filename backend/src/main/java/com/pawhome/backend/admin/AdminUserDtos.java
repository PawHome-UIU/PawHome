package com.pawhome.backend.admin;

import com.pawhome.backend.user.ApprovalStatus;
import com.pawhome.backend.user.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public final class AdminUserDtos {

    private AdminUserDtos() {}

    /**
     * One row of the admin Users table.
     *
     * NO PASSWORD HASH, and no token of any kind. An admin has no reason to see
     * either, and a DTO is the only place that boundary can be drawn -- return
     * the entity and every future field comes along automatically.
     */
    public record AdminUserRow(
            Long id,
            String fullName,
            String email,
            Role role,
            ApprovalStatus approvalStatus,
            boolean emailVerified,
            boolean active,
            Long shelterId,
            String shelterName,
            String jobTitle,
            LocalDateTime createdAt,

            /**
             * An invitation is out and has not been accepted.
             *
             * DERIVED, never stored. "Invited" is not a state of the person, it
             * is the existence of an unused, unexpired INVITE token -- and
             * user_tokens already records that exactly. A column here would be
             * a second copy of the same fact, free to disagree with the first.
             *
             * The admin table needs it because otherwise an invited account
             * looks identical to a broken one: approved, active, and email
             * unconfirmed.
             */
            boolean invitePending
    ) {

        /** Build a row when the shelter name and invite state are already known. */
        public static AdminUserRow of(com.pawhome.backend.user.User u,
                                      String shelterName, boolean invitePending) {
            return new AdminUserRow(u.getId(), u.getFullName(), u.getEmail(),
                    u.getRole(), u.getApprovalStatus(), u.isEmailVerified(),
                    u.isActive(), u.getShelterId(), shelterName, u.getJobTitle(),
                    u.getCreatedAt(), invitePending);
        }
    }

    /** Admin invites a staff member to a shelter. */
    public record InviteRequest(
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Email @Size(max = 160) String email,
            @NotNull(message = "Choose the shelter they are joining.") Long shelterId,
            @Size(max = 80) String jobTitle
    ) {}

    /**
     * Moving a staff member to another shelter.
     *
     * A record of one field rather than a bare @RequestParam, so the shelter id
     * arrives in the body like every other write on this controller and gains a
     * validation message somebody can read.
     */
    public record ShelterTransferRequest(
            @NotNull(message = "Choose the shelter they are moving to.") Long shelterId
    ) {}

    /** The invited person choosing their password. Public. */
    public record AcceptInviteRequest(
            @NotBlank String token,
            @NotBlank @Size(min = 8, max = 72) String password
    ) {}

    /**
     * Rejecting needs a reason the person can read.
     *
     * Same argument as declining an adoption: a refusal with no explanation is
     * the thing people complain about, and one sentence costs nothing.
     */
    public record RejectRequest(
            @NotBlank @Size(min = 10, max = 500,
                message = "Please write at least a sentence — this is what they will read.")
            String reason
    ) {}
}
