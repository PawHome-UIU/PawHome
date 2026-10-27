package com.pawhome.backend.user;

import com.pawhome.backend.auth.AuthService;
import com.pawhome.backend.auth.PasswordPolicy;
import com.pawhome.backend.security.ScopeGuard;
import com.pawhome.backend.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static com.pawhome.backend.auth.AuthDtos.AuthResponse;
import static com.pawhome.backend.auth.AuthDtos.MeResponse;
import static com.pawhome.backend.user.MeDtos.*;

/**
 * Your own account.
 *
 * THE SETTINGS PAGE USED TO SAVE NOTHING. It ran
 * `onSubmit={(e) => { e.preventDefault(); setSaved("profile"); }}` -- it
 * showed a success message and posted nowhere, and there was no endpoint it
 * could have posted to: AuthController had /me as a read and password change
 * existed only as the forgot-password email flow. This class is the other half.
 *
 * EVERY METHOD READS THE CALLER FROM THE SECURITY CONTEXT and none takes a
 * user id. That is not tidiness: an id parameter is a thing somebody can edit,
 * and "change my password" with a user id in the body is an account takeover
 * waiting for one missing check.
 */
@Service
@RequiredArgsConstructor
public class MeService {

    private static final Logger log = LoggerFactory.getLogger(MeService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final StorageService storage;
    private final AuthService authService;
    private final com.pawhome.backend.security.JwtService jwtService;
    private final ScopeGuard scopeGuard;

    private User me() {
        Long id = scopeGuard.require().id();
        return userRepository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such account."));
    }

    @Transactional
    public MeResponse updateProfile(ProfileRequest r) {
        User user = me();
        user.setFullName(r.fullName().trim());
        user.setPhone(blankToNull(r.phone()));
        // Only a staff member has one; letting an adopter set it would put a
        // job title on an account no screen shows one for.
        if (user.getRole() == Role.STAFF) {
            user.setJobTitle(blankToNull(r.jobTitle()));
        }
        userRepository.save(user);
        return authService.me(user);
    }

    /**
     * Change a password you already know, and end every other session.
     *
     * THE ORDER OF THE THREE CHECKS IS THE DESIGN.
     *
     *   1. the current password must match -- see PasswordChangeRequest;
     *   2. the new one must satisfy the same PasswordPolicy that registration
     *      and reset use, from the same class, so there is no back door where
     *      the weakest password in the system is one somebody chose later;
     *   3. it must actually be different, because "changed" is what the person
     *      will believe afterwards.
     *
     * THEN THE PART THAT MAKES IT WORTH DOING. A JWT is self-contained, so
     * until V11 a token stolen or left on a shared machine kept working for
     * its full life however the owner reacted -- and reacting is exactly what
     * a password change IS. Stamping tokens_valid_from retires every token
     * minted before this moment, all at once, which is what "change my
     * password" means to the person doing it.
     *
     * A FRESH TOKEN COMES BACK IN THE SAME RESPONSE, because the stamp would
     * otherwise retire the caller's own session too and sign them out of the
     * tab they are standing in. Truncated to the second, so it cannot land
     * after the new token's iat claim -- a JWT carries whole seconds, and a
     * stamp with milliseconds would make the replacement look older than the
     * retirement and refuse it on the very next request.
     */
    @Transactional
    public AuthResponse changePassword(PasswordChangeRequest r) {
        User user = me();

        if (!passwordEncoder.matches(r.currentPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That is not your current password.");
        }
        PasswordPolicy.check(r.newPassword());
        if (passwordEncoder.matches(r.newPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That is already your password.");
        }

        user.setPasswordHash(passwordEncoder.encode(r.newPassword()));
        user.setTokensValidFrom(
                LocalDateTime.now(ZoneOffset.UTC).withNano(0));
        userRepository.save(user);

        // Never the password, never the hash -- only that it happened, which
        // is the part anyone investigating an account would need.
        log.info("User {} changed their password; earlier tokens retired", user.getId());

        return new AuthResponse(jwtService.issue(user), jwtService.getExpirationMs(),
                authService.me(user));
    }

    /**
     * Upload an avatar.
     *
     * Store, save, then delete the old file -- the same order as the shelter
     * logo and for the same reason: deleting first and then failing to save
     * leaves a row pointing at a file that is gone, and the worst case this
     * way is an orphan nobody references.
     */
    @Transactional
    public MeResponse setAvatar(MultipartFile file) {
        User user = me();
        String previous = user.getAvatarPath();
        user.setAvatarPath(storage.store(file, "avatars"));
        userRepository.save(user);
        if (previous != null) storage.delete(previous);
        return authService.me(user);
    }

    @Transactional
    public MeResponse setPreferences(PreferencesRequest r) {
        User user = me();
        user.setNotifyApplications(r.notifyApplications());
        user.setNotifyBoarding(r.notifyBoarding());
        user.setNotifyHealth(r.notifyHealth());
        user.setNotifyEmail(r.notifyEmail());
        userRepository.save(user);
        return authService.me(user);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
