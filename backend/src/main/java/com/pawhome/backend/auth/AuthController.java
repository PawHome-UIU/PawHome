package com.pawhome.backend.auth;

import com.pawhome.backend.security.AppUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import static com.pawhome.backend.auth.AuthDtos.*;

/**
 * Authentication endpoints.
 *
 * No logic here, by the project's convention: the controller validates its
 * input, calls the service and shapes a response. Every decision about who may
 * do what lives in AuthService and in the security package.
 *
 * /register and /login are the only two endpoints in the whole API that are
 * permitted anonymously by name in SecurityConfig. Everything else is
 * authenticated by default, so a new controller is locked down the moment it is
 * written rather than when somebody remembers to lock it.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final VerificationService verificationService;
    private final com.pawhome.backend.admin.InviteService inviteService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisteredResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    /**
     * Confirm an email address.
     *
     * POST, NOT GET, even though this is reached from a link in an email. The
     * link opens a React page carrying the token in the URL; that page then
     * POSTs it here.
     *
     * A GET endpoint would be redeemed by the first thing that fetches the URL,
     * and corporate mail scanners and link previewers fetch every link in every
     * message before the person sees it. The token would be spent, and the real
     * click would get "already used".
     *
     * Verifying grants nothing but the flag -- it does not sign anyone in.
     */
    @PostMapping("/verify-email")
    public AcknowledgedResponse verifyEmail(@RequestParam("token") String token) {
        verificationService.verifyEmail(token);
        return new AcknowledgedResponse("Your email is confirmed. You can sign in now.");
    }

    @PostMapping("/resend-verification")
    public AcknowledgedResponse resendVerification(@Valid @RequestBody EmailOnlyRequest request) {
        verificationService.resendVerification(request.email());
        return SAME_ANSWER_EITHER_WAY;
    }

    @PostMapping("/forgot-password")
    public AcknowledgedResponse forgotPassword(@Valid @RequestBody EmailOnlyRequest request) {
        verificationService.requestPasswordReset(request.email());
        return SAME_ANSWER_EITHER_WAY;
    }

    @PostMapping("/reset-password")
    public AcknowledgedResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        verificationService.resetPassword(request.token(), request.password());
        return new AcknowledgedResponse("Your password has been changed. You can sign in now.");
    }

    /**
     * One reply for "sent" and for "no such account".
     *
     * Different answers would turn these endpoints into a way to find out who
     * has an account here.
     */
    private static final AcknowledgedResponse SAME_ANSWER_EITHER_WAY =
            new AcknowledgedResponse("If an account exists for that address, a link is on its way.");

    /**
     * An invited staff member choosing their password.
     *
     * PUBLIC, and it has to be: they have no password yet, so there is nothing
     * to sign in with. The one-time INVITE token is their entire authority,
     * and InviteService checks the type on redemption so a password-reset link
     * cannot be used here instead.
     */
    @PostMapping("/accept-invite")
    public AcknowledgedResponse acceptInvite(
            @Valid @RequestBody com.pawhome.backend.admin.AdminUserDtos.AcceptInviteRequest request) {
        inviteService.accept(request.token(), request.password());
        return new AcknowledgedResponse(
                "Your password is set. You can sign in now.");
    }

    /**
     * Who am I?
     *
     * The frontend calls this on boot to decide which portal to render, rather
     * than reading the role claim out of the token. Same reason the filter
     * reloads the user: a deactivated or transferred account should find out
     * immediately, not when its token expires.
     *
     * Returns 401 rather than an empty body when there is no session, so the
     * client can tell "signed out" from "signed in with nothing to show".
     */
    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(@AuthenticationPrincipal AppUser me) {
        if (me == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(authService.me(me));
    }
}
