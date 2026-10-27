package com.pawhome.backend.admin;

import com.pawhome.backend.auth.PasswordPolicy;
import com.pawhome.backend.mail.MailService;
import com.pawhome.backend.security.ScopeGuard;
import com.pawhome.backend.shelter.Shelter;
import com.pawhome.backend.shelter.ShelterRepository;
import com.pawhome.backend.shelter.ShelterStatus;
import com.pawhome.backend.token.TokenService;
import com.pawhome.backend.token.TokenType;
import com.pawhome.backend.user.ApprovalStatus;
import com.pawhome.backend.user.Role;
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

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * Staff invitations.
 *
 * The account is created up front, already APPROVED, and emailed a one-time
 * link that sets its password. Two things are worth stating plainly:
 *
 * WHY APPROVED AND NOT PENDING. The approval queue exists because anyone can
 * register themselves as staff and claim any shelter. An admin typing somebody
 * into a specific shelter IS the vetting step -- putting them in a queue for
 * the same admin to approve is a form they fill in twice.
 *
 * WHY NOT emailVerified. The admin typed the address; the person has not shown
 * they can read it. Accepting the invitation does show that, because the link
 * only ever arrives at that address -- so verification is set THERE, on the
 * same evidence a reset uses, and not a moment earlier. Until then the account
 * fails the enabled check and cannot sign in, which is exactly right.
 *
 * THE ACCOUNT IS NOT PASSWORDLESS. password_hash is NOT NULL, and leaving a
 * sentinel like "" or "!" in it means every login path needs a special case,
 * and one of them will eventually forget. It gets a BCrypt hash of 32 random
 * bytes instead: a real hash that no input can match. Nothing else in the
 * system has to know this account is unusual.
 */
@Service
@RequiredArgsConstructor
public class InviteService {

    private static final Logger log = LoggerFactory.getLogger(InviteService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final ShelterRepository shelterRepository;
    private final TokenService tokenService;
    private final MailService mailService;
    private final PasswordEncoder passwordEncoder;
    private final ScopeGuard scopeGuard;

    /** Admin invites a staff member to a shelter. */
    @Transactional
    public AdminUserDtos.AdminUserRow invite(String fullName, String email,
                                             Long shelterId, String jobTitle) {
        String normalised = email.trim().toLowerCase();

        Shelter shelter = shelterRepository.findById(shelterId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "That shelter does not exist."));

        // An INACTIVE shelter takes no new staff. The foreign key would happily
        // accept it and the person would arrive at a shelter that has left the
        // network, with no way to tell.
        if (shelter.getStatus() == ShelterStatus.INACTIVE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That shelter is inactive, so it cannot take new staff.");
        }

        Optional<User> existing = userRepository.findByEmail(normalised);
        if (existing.isPresent()) {
            // Deliberately specific. This endpoint is ADMIN-only, so there is no
            // account-enumeration concern here -- and an admin who is told
            // "that didn't work" learns nothing they can act on.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Somebody is already registered with that address.");
        }

        User user = userRepository.save(User.builder()
                .fullName(fullName.trim())
                .email(normalised)
                .passwordHash(passwordEncoder.encode(unguessable()))
                .role(Role.STAFF)
                .shelterId(shelterId)
                .jobTitle(jobTitle == null || jobTitle.isBlank() ? null : jobTitle.trim())
                // The admin naming them is the vetting. See the class comment.
                .approvalStatus(ApprovalStatus.APPROVED)
                // Proven when they open the link, not now.
                .emailVerified(false)
                .active(true)
                .build());

        String invitedBy = scopeGuard.require().domain().getFullName();
        mailService.sendStaffInvite(user.getEmail(), user.getFullName(),
                shelter.getName(), invitedBy,
                tokenService.issue(user.getId(), TokenType.INVITE));

        log.info("Admin {} invited {} to shelter {}",
                scopeGuard.require().id(), user.getId(), shelterId);

        return AdminUserDtos.AdminUserRow.of(user, shelter.getName(), true);
    }

    /** Re-send an invitation whose link expired or never arrived. */
    @Transactional
    public void resendInvite(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such user."));

        if (user.isEmailVerified()) {
            // They already accepted. Re-sending would issue a link that sets
            // the password of a working account -- which is a password reset,
            // and there is a separate flow for that with a one-hour window.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That invitation has already been accepted. "
                    + "Send a password reset instead.");
        }

        String shelterName = user.getShelterId() == null ? "PawHome"
                : shelterRepository.findById(user.getShelterId())
                        .map(Shelter::getName).orElse("PawHome");

        // issue() invalidates any outstanding link of this type first, so the
        // previous invitation stops working the moment this one is sent.
        mailService.sendStaffInvite(user.getEmail(), user.getFullName(),
                shelterName, scopeGuard.require().domain().getFullName(),
                tokenService.issue(user.getId(), TokenType.INVITE));

        log.info("Invitation re-sent to user {}", userId);
    }

    /**
     * The invited person sets their password. PUBLIC -- they have no account
     * to sign in with yet; the token is the whole of their authority.
     */
    @Transactional
    public void accept(String rawToken, String password) {
        // The same rules as registration, from the same class. Two copies
        // would drift, and you would find out when somebody set a password
        // here that they could never have set at sign-up.
        PasswordPolicy.check(password);

        Optional<Long> userId = tokenService.redeem(rawToken, TokenType.INVITE);
        if (userId.isEmpty()) {
            boolean stale = tokenService.wasIssuedButUnusable(rawToken);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, stale
                    ? "That invitation has already been used or has expired. "
                      + "Ask your administrator to send a new one."
                    : "That invitation link is not valid.");
        }

        User user = userRepository.findById(userId.get()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "That account no longer exists."));

        user.setPasswordHash(passwordEncoder.encode(password));

        // Opening a link that was only ever sent to that address proves the
        // person can read it -- the same evidence the verification link gives.
        user.setEmailVerified(true);
        userRepository.save(user);

        log.info("Invitation accepted by user {}", user.getId());
    }

    /**
     * A password no input can match.
     *
     * Not "" and not a sentinel: BCrypt.matches against a non-BCrypt string
     * both logs a warning on every attempt and invites a future `if
     * (hash.isEmpty())` shortcut somewhere in the login path. A real hash of
     * 256 random bits behaves like any other password and can never be
     * guessed, so no other code has to know this account is unusual.
     */
    private static String unguessable() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
