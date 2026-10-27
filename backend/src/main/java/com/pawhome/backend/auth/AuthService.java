package com.pawhome.backend.auth;

import com.pawhome.backend.security.AppUser;
import com.pawhome.backend.security.JwtService;
import com.pawhome.backend.security.LoginRateLimitFilter;
import com.pawhome.backend.mail.MailService;
import com.pawhome.backend.token.TokenService;
import com.pawhome.backend.token.TokenType;
import com.pawhome.backend.user.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Optional;

import static com.pawhome.backend.auth.AuthDtos.*;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginRateLimitFilter rateLimit;
    private final TokenService tokenService;
    private final MailService mailService;

    /**
     * Create an account.
     *
     * THE ROLE IN THE REQUEST IS AN INPUT, NOT AN INSTRUCTION. This endpoint is
     * unauthenticated, so anything it accepts, an attacker can send. Two rules
     * follow, and they are the entire security of this method:
     *
     *   1. ADMIN can never be created here. Admins are seeded (V2) or promoted
     *      by an existing admin. Without this, registration is an "everyone is
     *      an admin" button.
     *
     *   2. STAFF is created PENDING. Claiming to work at a shelter is not the
     *      same as working there, so an admin approves before the account can
     *      do anything. The JwtAuthFilter's enabled-check is what makes that
     *      stick on every request, not just at login.
     */
    @Transactional
    public RegisteredResponse register(RegisterRequest req) {
        String email = normalise(req.email());

        // Checked here rather than only by @Size on the DTO, so registration
        // and password reset run the identical rules from the identical class.
        PasswordPolicy.check(req.password());

        if (req.role() == Role.ADMIN) {
            // 403, not 400: this is a refusal, not a malformed request, and
            // saying so plainly is better than pretending the field was wrong.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Admin accounts cannot be self-registered.");
        }

        if (req.role() == Role.STAFF && req.shelterId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Staff must name the shelter they are joining.");
        }
        if (req.role() == Role.ADOPTER && req.shelterId() != null) {
            // ck_users_staff_shelter would reject this at the database anyway;
            // failing here gives a readable message instead of a constraint dump.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Adopters do not belong to a shelter.");
        }

        if (userRepository.existsByEmail(email)) {
            // This DOES disclose that an email is registered, and for a signup
            // form that is the accepted trade: the alternative is telling
            // someone their account was created when it was not. The
            // /forgot-password flow, where the same disclosure has no upside,
            // stays deliberately silent.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "An account with that email already exists.");
        }

        boolean staff = req.role() == Role.STAFF;

        User user = User.builder()
                .fullName(req.fullName().trim())
                .email(email)
                .passwordHash(passwordEncoder.encode(req.password()))
                .phone(blankToNull(req.phone()))
                .role(req.role())
                .approvalStatus(staff ? ApprovalStatus.PENDING : ApprovalStatus.APPROVED)
                .active(true)
                .shelterId(staff ? req.shelterId() : null)
                .build();

        User saved = userRepository.save(user);
        log.info("Registered user {} as {} ({})", saved.getId(), saved.getRole(), saved.getApprovalStatus());

        // Sent asynchronously: the account is already committed, and a slow or
        // unreachable SMTP server must not turn a successful registration into
        // an error the person cannot act on.
        mailService.sendVerification(
                saved.getEmail(),
                saved.getFullName(),
                tokenService.issue(saved.getId(), TokenType.EMAIL_VERIFICATION));

        return new RegisteredResponse(
                saved.getId(),
                saved.getEmail(),
                saved.getRole(),
                staff,
                staff
                        ? "Check your email to confirm your address. Your shelter account "
                          + "then waits for an administrator to approve it."
                        : "Check your email &mdash; we've sent a link to confirm your address. "
                          + "You can sign in once you've clicked it."
        );
    }

    /**
     * Exchange an email and password for a token.
     *
     * EVERY FAILURE RETURNS THE SAME 401 AND THE SAME MESSAGE. Distinguishing
     * "no such user" from "wrong password" turns this endpoint into a way to
     * enumerate who has an account, which for an adoption service means
     * disclosing who is looking to adopt.
     *
     * A disabled account is the deliberate exception: "waiting for approval" is
     * something the person needs to be told, and they already proved the
     * password, so it discloses nothing they did not know.
     */
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        String email = normalise(req.email());

        // The per-email half of the rate limit. The filter cannot do this: the
        // email lives in the JSON body, and reading the body there would consume
        // the stream this method still needs.
        //
        // 429 is returned WHETHER OR NOT the account exists, so a throttled
        // response reveals nothing that an unthrottled one would not.
        if (rateLimit.emailIsThrottled(email)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many failed attempts for this account. Please wait a few minutes.");
        }

        Optional<User> found = userRepository.findByEmail(email);

        // Hash even when the user does not exist. Skipping it returns in
        // microseconds instead of ~100ms, and that gap alone reveals which
        // emails are registered -- the timing version of the message we were
        // careful not to send.
        String hash = found.map(User::getPasswordHash).orElse(DUMMY_HASH);
        boolean matches = passwordEncoder.matches(req.password(), hash);

        if (found.isEmpty() || !matches) {
            // Counted for the email too, not just the IP -- otherwise a botnet
            // spreads one account's guesses across a thousand addresses and the
            // per-IP limit never notices.
            rateLimit.noteEmailFailure(email);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "That email and password do not match.");
        }

        User user = found.get();

        if (!user.isEnabled()) {
            // Order matters: check verification FIRST. An unverified staff
            // sign-up fails both rules, and "confirm your email" is the one
            // they can actually do something about -- telling them to wait for
            // an admin who is waiting for them is a deadlock.
            String why;
            if (!user.isEmailVerified()) {
                why = "Please confirm your email address first. Check your inbox for the link.";
            } else if (user.getApprovalStatus() == ApprovalStatus.PENDING) {
                why = "Your account is still waiting for an administrator to approve it.";
            } else if (user.getApprovalStatus() == ApprovalStatus.REJECTED) {
                why = "Your account request was not approved.";
            } else {
                why = "Your account has been deactivated. Contact an administrator.";
            }
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, why);
        }

        // They proved the password, so the slate is clean.
        rateLimit.clearEmail(email);
        return new AuthResponse(jwtService.issue(user), jwtService.getExpirationMs(), me(user));
    }

    public MeResponse me(User user) {
        return new MeResponse(
                user.getId(), user.getFullName(), user.getEmail(), user.getRole(),
                user.getShelterId(), user.getJobTitle(), user.getAvatarPath(),
                user.isNotifyApplications(), user.isNotifyBoarding(),
                user.isNotifyHealth(), user.isNotifyEmail());
    }

    public MeResponse me(AppUser appUser) {
        return me(appUser.domain());
    }

    /**
     * A real BCrypt hash of a value nobody knows, so the comparison above costs
     * the same as a genuine one. It must be a valid hash or the encoder returns
     * early and the timing gap comes straight back.
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private static String normalise(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
