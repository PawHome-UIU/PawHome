package com.pawhome.backend.auth;

import com.pawhome.backend.user.Role;
import jakarta.validation.constraints.*;

/**
 * Request and response shapes for /api/v1/auth.
 *
 * Records, because these are values that never change after construction, and
 * the compactness keeps the whole contract readable on one screen.
 */
public final class AuthDtos {

    private AuthDtos() {}

    /**
     * Registration.
     *
     * `role` is accepted but NOT trusted as given -- see AuthService. Anyone can
     * post role=ADMIN here; the service is what decides that only ADOPTER and
     * STAFF are self-service, and that STAFF lands PENDING.
     */
    public record RegisterRequest(
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Email @Size(max = 160) String email,
            // Bean validation catches only the crude cases so a null or empty
            // value never reaches the service. The real rules -- uppercase,
            // special character -- live in PasswordPolicy, which registration
            // and password reset both call, so they cannot drift apart.
            @NotBlank @Size(min = 8, max = 72) String password,
            @Size(max = 30) String phone,
            @NotNull Role role,
            /** Required when role is STAFF -- ck_users_staff_shelter. */
            Long shelterId
    ) {}

    public record EmailOnlyRequest(
            @NotBlank @Email String email
    ) {}

    public record ResetPasswordRequest(
            @NotBlank String token,
            // See RegisterRequest: PasswordPolicy has the real rules.
            @NotBlank @Size(min = 8, max = 72) String password
    ) {}

    /** Deliberately says nothing about whether an account was found. */
    public record AcknowledgedResponse(String message) {}

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password
    ) {}

    /** What the browser stores after a successful login. */
    public record AuthResponse(
            String token,
            long expiresInMs,
            MeResponse user
    ) {}

    /**
     * The current user.
     *
     * No password hash, no approval internals beyond what the UI must show.
     * `shelterId` is null for everyone but staff.
     */
    public record MeResponse(
            Long id,
            String fullName,
            String email,
            Role role,
            Long shelterId,
            String jobTitle,
            String avatarPath,
            /**
             * The notification switches, carried on the SAME response the app
             * already fetches to know who is signed in.
             *
             * A separate GET /me/preferences would be a second round trip for
             * four booleans, and a Settings page that can render before it
             * knows which switches are on shows every one of them off for a
             * moment -- which reads as "your preferences were lost".
             */
            boolean notifyApplications,
            boolean notifyBoarding,
            boolean notifyHealth,
            boolean notifyEmail
    ) {}

    /**
     * Registration that did not produce a session.
     *
     * A staff sign-up is PENDING, so there is no token to hand back. Saying so
     * explicitly beats returning a token that fails on the very next request.
     */
    public record RegisteredResponse(
            Long id,
            String email,
            Role role,
            boolean awaitingApproval,
            String message
    ) {}
}
