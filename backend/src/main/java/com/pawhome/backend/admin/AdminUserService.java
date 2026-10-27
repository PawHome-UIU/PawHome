package com.pawhome.backend.admin;

import com.pawhome.backend.mail.MailService;
import com.pawhome.backend.security.AppUser;
import com.pawhome.backend.security.ScopeGuard;
import com.pawhome.backend.shelter.ShelterDtos;
import com.pawhome.backend.shelter.ShelterService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static com.pawhome.backend.admin.AdminUserDtos.*;

/**
 * What an administrator can do to accounts.
 *
 * THE STAFF APPROVAL QUEUE LIVES HERE. A staff sign-up is created PENDING and
 * can do nothing until an admin acts; until this service existed there was no
 * way to act, so every staff account ever registered was stuck. The Users page
 * was showing placeholder rows from a JavaScript file, which looked like a
 * working queue and was not connected to anything.
 *
 * Every method is reachable only under /api/v1/admin/**, which SecurityConfig
 * restricts to ADMIN. ScopeGuard is still consulted for the self-protection
 * rules below, because "is an admin" does not answer "may an admin do this to
 * THIS row" -- and the row that needs protecting is their own.
 */
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    private final UserRepository userRepository;
    private final JdbcTemplate jdbc;
    private final MailService mailService;
    private final com.pawhome.backend.notification.NotificationService notifications;
    private final TokenService tokenService;
    private final ScopeGuard scopeGuard;
    // Service, not ShelterRepository: reading another module's table directly
    // is how two modules end up disagreeing about what a shelter is.
    private final ShelterService shelterService;

    /**
     * Every account, newest first.
     *
     * Hand-written SQL for the shelter name: the alternative is a @ManyToOne on
     * User, which would then load a Shelter on every single request, because
     * JwtAuthFilter reads that entity on every request. One join here beats a
     * permanent cost everywhere.
     *
     * Values are BOUND, never concatenated -- there is no user input in this
     * query at all, and it stays that way.
     */
    @Transactional(readOnly = true)
    public List<AdminUserRow> list() {
        return jdbc.query("""
            SELECT  u.id, u.full_name, u.email, u.role, u.approval_status,
                    u.email_verified, u.is_active, u.shelter_id,
                    s.name AS shelter_name, u.job_title, u.created_at,
                    -- An outstanding invitation. Correlated EXISTS rather than
                    -- a join, because a join to user_tokens would duplicate the
                    -- user row once per token they have ever been sent.
                    EXISTS (SELECT 1
                              FROM user_tokens t
                             WHERE t.user_id     = u.id
                               AND t.token_type  = 'INVITE'
                               AND t.used_at     IS NULL
                               AND t.expires_at  > UTC_TIMESTAMP()) AS invite_pending
            FROM        users    u
            LEFT JOIN   shelters s ON s.id = u.shelter_id
            ORDER BY    u.created_at DESC
            """, (rs, i) -> new AdminUserRow(
                    rs.getLong("id"),
                    rs.getString("full_name"),
                    rs.getString("email"),
                    Role.valueOf(rs.getString("role")),
                    ApprovalStatus.valueOf(rs.getString("approval_status")),
                    rs.getBoolean("email_verified"),
                    rs.getBoolean("is_active"),
                    (Long) rs.getObject("shelter_id"),
                    rs.getString("shelter_name"),
                    rs.getString("job_title"),
                    rs.getTimestamp("created_at").toLocalDateTime(),
                    rs.getBoolean("invite_pending")));
    }

    /** Approve a pending staff sign-up. */
    @Transactional
    public AdminUserRow approve(Long userId) {
        User user = load(userId);

        if (user.getRole() != Role.STAFF) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only shelter staff accounts need approving.");
        }
        if (user.getApprovalStatus() == ApprovalStatus.APPROVED) {
            // Idempotent: two admins clicking at once should not produce an
            // error for the slower one.
            return row(user);
        }

        user.setApprovalStatus(ApprovalStatus.APPROVED);
        userRepository.save(user);
        log.info("Admin approved staff account {}", userId);

        // They still cannot sign in until the address is confirmed, so an
        // unverified account gets a fresh link with the approval -- otherwise
        // "you're approved" arrives and the login still refuses them.
        if (!user.isEmailVerified()) {
            mailService.sendVerification(user.getEmail(), user.getFullName(),
                    tokenService.issue(user.getId(), TokenType.EMAIL_VERIFICATION));
        }

        // Not gated by any preference -- V10 says why: an account somebody
        // cannot use, with no explanation, is not a quieter experience.
        notifications.raise(user.getId(),
                com.pawhome.backend.notification.NotificationType.STAFF_APPROVAL,
                "Your staff account was approved",
                "You can sign in and start working with your shelter's pets, "
                + "applications and boarding.",
                "/staff");

        return row(user);
    }

    /** Turn down a pending staff sign-up, with a reason. */
    @Transactional
    public AdminUserRow reject(Long userId, String reason) {
        User user = load(userId);

        if (user.getRole() != Role.STAFF) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only shelter staff accounts need approving.");
        }

        user.setApprovalStatus(ApprovalStatus.REJECTED);
        userRepository.save(user);

        notifications.raise(user.getId(),
                com.pawhome.backend.notification.NotificationType.STAFF_APPROVAL,
                "Your staff account was not approved",
                // The reason is optional at the call site, so the fallback has
                // to be a sentence rather than an empty body that renders as a
                // notification with a title and nothing under it.
                reason == null || reason.isBlank()
                        ? "Please contact your shelter's administrator."
                        : reason.trim(),
                "/login");

        log.info("Admin rejected staff account {}", userId);
        return row(user);
    }

    /**
     * Switch an account off, or back on.
     *
     * An admin cannot deactivate themselves. Not a hypothetical: with one admin
     * account, doing so locks everybody out of the admin portal permanently,
     * and the only way back is editing the database by hand.
     */
    @Transactional
    public AdminUserRow setActive(Long userId, boolean active) {
        AppUser me = scopeGuard.require();
        if (!active && me.id().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "You cannot deactivate your own account.");
        }

        User user = load(userId);
        user.setActive(active);
        userRepository.save(user);
        log.info("Admin set user {} active={}", userId, active);
        return row(user);
    }

    /**
     * Move a staff member to another shelter.
     *
     * THIS IS AN ACCESS-CONTROL CHANGE WEARING AN ADMINISTRATIVE HAT, and the
     * reason it is logged at WARN rather than INFO. ScopeGuard reads
     * shelter_id off the user row on every single request, so the moment this
     * commits the person is reading the new shelter's pets, applications and
     * boarding and has lost sight of the old one's -- mid-session, on their
     * very next click, with no sign-out in between. That immediacy is the
     * behaviour we want; it is also worth being able to find in a log.
     *
     * Idempotent on purpose: two admins picking the same shelter at once
     * should not produce an error for the slower one.
     */
    @Transactional
    public AdminUserRow setShelter(Long userId, Long shelterId) {
        User user = load(userId);

        // ck_users_staff_shelter: a STAFF row must have a shelter_id and every
        // other role must not. Refusing here gives a sentence instead of a
        // constraint-violation dump from the database.
        if (user.getRole() != Role.STAFF) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only shelter staff belong to a shelter.");
        }

        ShelterDtos.ShelterRow shelter;
        try {
            shelter = shelterService.get(shelterId);
        } catch (ResponseStatusException e) {
            // A 404 from the shelter module is not a 404 for this request: the
            // user exists, it is the id in the body that is wrong. Anything
            // else is not ours to reinterpret.
            if (e.getStatusCode() != HttpStatus.NOT_FOUND) throw e;
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That shelter does not exist.");
        }

        // Same rule as an invitation. The foreign key would accept it happily
        // and the person would arrive at a shelter that has left the network.
        if (shelter.status() == ShelterStatus.INACTIVE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That shelter is inactive, so it cannot take staff.");
        }

        Long from = user.getShelterId();
        if (shelterId.equals(from)) return row(user);

        user.setShelterId(shelterId);
        userRepository.save(user);

        log.warn("Admin {} moved staff user {} from shelter {} to shelter {} "
                 + "-- their visible data changes on the next request",
                scopeGuard.require().id(), userId, from, shelterId);

        return row(user);
    }

    /**
     * Send a fresh confirmation link to someone who never got one.
     *
     * The reason this exists: every account registered while app.mail.enabled
     * was false had its link written to the log and nowhere else. Those people
     * cannot sign in and have no way to fix it themselves beyond guessing that
     * a resend form exists. An admin can now push a new link from the Users
     * table.
     */
    @Transactional
    public void resendVerification(Long userId) {
        User user = load(userId);
        if (user.isEmailVerified()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That address is already confirmed.");
        }
        mailService.sendVerification(user.getEmail(), user.getFullName(),
                tokenService.issue(user.getId(), TokenType.EMAIL_VERIFICATION));
        log.info("Admin resent verification to user {}", userId);
    }

    /**
     * Mark an address confirmed without an email round trip.
     *
     * A deliberate back door, and worth being honest about: it lets an admin
     * assert that an address is real without anyone proving it. It exists
     * because the alternative during development -- editing the database by
     * hand -- is worse, and because accounts stranded by mail being switched
     * off need a way out.
     *
     * It is logged at WARN precisely because it bypasses a check.
     */
    @Transactional
    public AdminUserRow forceVerify(Long userId) {
        User user = load(userId);
        if (!user.isEmailVerified()) {
            user.setEmailVerified(true);
            userRepository.save(user);
            log.warn("Admin manually marked user {} ({}) as email-verified, "
                     + "bypassing the confirmation link", userId, user.getEmail());
        }
        return row(user);
    }

    // ---------------------------------------------------------------- helpers

    private User load(Long id) {
        return userRepository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such user."));
    }

    private AdminUserRow row(User u) {
        String shelterName = u.getShelterId() == null ? null
                : jdbc.query("SELECT name FROM shelters WHERE id = ?",
                        rs -> rs.next() ? rs.getString(1) : null, u.getShelterId());

        Boolean invitePending = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM user_tokens
                                WHERE user_id = ? AND token_type = 'INVITE'
                                  AND used_at IS NULL
                                  AND expires_at > UTC_TIMESTAMP())
                """, Boolean.class, u.getId());

        return AdminUserRow.of(u, shelterName, Boolean.TRUE.equals(invitePending));
    }

    /** Counts for the dashboard tiles, in one round trip. */
    @Transactional(readOnly = true)
    public Map<String, Object> counts() {
        return jdbc.queryForMap("""
            SELECT  COUNT(*)                                              AS total,
                    SUM(role = 'STAFF')                                   AS staff,
                    SUM(role = 'ADOPTER')                                 AS adopters,
                    SUM(role = 'STAFF' AND approval_status = 'PENDING')   AS awaitingApproval,
                    SUM(email_verified = FALSE)                           AS unverified
            FROM users
            """);
    }
}
