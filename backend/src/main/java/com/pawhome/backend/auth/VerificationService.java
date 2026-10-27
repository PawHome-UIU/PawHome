package com.pawhome.backend.auth;

import com.pawhome.backend.mail.MailService;
import com.pawhome.backend.token.TokenService;
import com.pawhome.backend.token.TokenType;
import com.pawhome.backend.user.User;
import com.pawhome.backend.user.UserRepository;
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

/**
 * Email confirmation and password reset.
 *
 * EVERY METHOD HERE IS UNAUTHENTICATED AND PUBLIC, so each one is written on the
 * assumption that the caller is hostile. Three rules follow, and they are why
 * the responses look oddly uninformative:
 *
 *   1. Requesting a link NEVER says whether the account exists. Otherwise this
 *      is an oracle for "does this person have a PawHome account", which for an
 *      adoption service discloses who is looking to adopt.
 *
 *   2. Resetting a password does NOT sign the person in. A reset link arriving
 *      in a compromised inbox should give an attacker one password change --
 *      loud, and noticed -- rather than a silent session.
 *
 *   3. A used or expired token is distinguishable from a nonsense one, because
 *      you had to hold the real token to ask, and "expired, here's a new one"
 *      is far kinder than a flat refusal.
 */
@Service
@RequiredArgsConstructor
public class VerificationService {

    private static final Logger log = LoggerFactory.getLogger(VerificationService.class);

    private final UserRepository userRepository;
    private final TokenService tokenService;
    private final MailService mailService;
    private final PasswordEncoder passwordEncoder;

    // ------------------------------------------------------- email verification

    /** Redeem a verification link. */
    @Transactional
    public void verifyEmail(String rawToken) {
        Optional<Long> userId = tokenService.redeem(rawToken, TokenType.EMAIL_VERIFICATION);

        if (userId.isEmpty()) {
            boolean stale = tokenService.wasIssuedButUnusable(rawToken);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, stale
                    ? "That link has already been used or has expired. Ask for a new one."
                    : "That verification link is not valid.");
        }

        User user = userRepository.findById(userId.get()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "That account no longer exists."));

        // Already-verified is a success, not an error: people click links twice,
        // and mail scanners click them before the person does.
        if (!user.isEmailVerified()) {
            user.setEmailVerified(true);
            userRepository.save(user);
            log.info("Email verified for user {}", user.getId());
        }
    }

    /**
     * Send a fresh verification link.
     *
     * Silent about whether the address is registered, and silent about whether
     * it was already verified -- both would answer a question the caller has no
     * business asking.
     */
    @Transactional
    public void resendVerification(String email) {
        userRepository.findByEmail(normalise(email))
                .filter(u -> !u.isEmailVerified())
                .ifPresent(u -> mailService.sendVerification(
                        u.getEmail(), u.getFullName(),
                        tokenService.issue(u.getId(), TokenType.EMAIL_VERIFICATION)));
    }

    // ----------------------------------------------------------- password reset

    /**
     * Start a password reset.
     *
     * Returns nothing and reveals nothing. The controller replies with the same
     * "if an account exists, a link is on its way" whatever happened here --
     * which is what the frontend's Forgot Password page has been claiming since
     * it was written, and until now was not true of anything.
     */
    @Transactional
    public void requestPasswordReset(String email) {
        userRepository.findByEmail(normalise(email))
                // An unverified address has never been proved to belong to this
                // person, so sending a reset link to it would be handing the
                // account to whoever typed the address in at registration.
                .filter(User::isEmailVerified)
                .ifPresent(u -> mailService.sendPasswordReset(
                        u.getEmail(), u.getFullName(),
                        tokenService.issue(u.getId(), TokenType.PASSWORD_RESET)));
    }

    /** Redeem a reset link and set the new password. */
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        // The same rules as registration, from the same class. Two copies
        // would drift, and the way you would find out is somebody setting a
        // password at reset that they could never have set at signup.
        PasswordPolicy.check(newPassword);

        Optional<Long> userId = tokenService.redeem(rawToken, TokenType.PASSWORD_RESET);
        if (userId.isEmpty()) {
            boolean stale = tokenService.wasIssuedButUnusable(rawToken);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, stale
                    ? "That reset link has already been used or has expired. Ask for a new one."
                    : "That reset link is not valid.");
        }

        User user = userRepository.findById(userId.get()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "That account no longer exists."));

        user.setPasswordHash(passwordEncoder.encode(newPassword));

        // Someone who can receive mail at the address has now proved they own
        // it, which is the same thing the verification link proves.
        user.setEmailVerified(true);

        userRepository.save(user);
        log.info("Password reset for user {}", user.getId());

        // NOTE: existing JWTs stay valid until they expire. Tokens are
        // stateless and there is no revocation list yet, so a reset does not
        // sign other devices out. Worth fixing before this handles anything
        // that matters -- a stolen token survives the password change.
    }

    private static String normalise(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
