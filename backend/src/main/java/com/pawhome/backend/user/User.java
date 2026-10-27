package com.pawhome.backend.user;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A user account. Maps the `users` table from V1__init.sql.
 *
 * ddl-auto is `validate`, so this class does not create anything -- it is
 * checked against what Flyway built, and a mismatch fails startup rather than
 * silently altering the schema.
 *
 * shelterId IS A PLAIN LONG, not a @ManyToOne Shelter. That is the project's
 * convention: foreign keys stay as ids and are resolved in the service when
 * they are actually needed. It keeps this entity loadable on every single
 * request (the auth filter does exactly that) without dragging a Shelter and
 * its collections along for the ride.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    /** Unique (uq_users_email). Stored lower-cased -- see UserService. */
    @Column(nullable = false, length = 160)
    private String email;

    /**
     * BCrypt hash, never the password.
     *
     * The column is VARCHAR(100) and a BCrypt hash is 60 characters, so there
     * is room; it is not a place to store anything longer without a migration.
     */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(length = 30)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false)
    @Builder.Default
    private ApprovalStatus approvalStatus = ApprovalStatus.APPROVED;

    /**
     * Whether the address has been confirmed by clicking a link.
     *
     * Separate from approvalStatus on purpose: an adopter is APPROVED the
     * instant they register, and must still not be able to sign in using
     * somebody else's email address.
     */
    @Column(name = "email_verified", nullable = false)
    @Builder.Default
    private boolean emailVerified = false;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    /**
     * The shelter a STAFF member belongs to; null for everyone else.
     *
     * ck_users_staff_shelter enforces both halves of that at the database
     * level, so a bug here surfaces as a constraint violation rather than as a
     * staff account that can somehow see every shelter.
     */
    @Column(name = "shelter_id")
    private Long shelterId;

    @Column(name = "job_title", length = 80)
    private String jobTitle;

    @Column(name = "avatar_path", length = 255)
    private String avatarPath;

    // ------------------------------------------------- notification choices
    //
    // Four columns rather than a preferences table: each is single-valued and
    // belongs to exactly this user, so a second table would be another row to
    // fetch and a join to get wrong, for no normalisation these do not already
    // have. See V10, which also explains why the mock's "matches" and
    // "newsletter" switches are not among them -- nothing sends either.

    /** APPLICATION_SUBMITTED and APPLICATION_DECIDED. */
    @Column(name = "notify_applications", nullable = false)
    @Builder.Default
    private boolean notifyApplications = true;

    /** BOARDING_DECIDED and BOARDING_UPDATE. */
    @Column(name = "notify_boarding", nullable = false)
    @Builder.Default
    private boolean notifyBoarding = true;

    /** The vaccination reminders HealthReminderJob already sends. */
    @Column(name = "notify_health", nullable = false)
    @Builder.Default
    private boolean notifyHealth = true;

    /**
     * The CHANNEL, not a topic. FALSE still writes the notification row and
     * still lights the bell -- it only stops the email, which is why it is
     * orthogonal to the three above rather than a fourth entry in the list.
     */
    @Column(name = "notify_email", nullable = false)
    @Builder.Default
    private boolean notifyEmail = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Tokens issued before this instant are refused. NULL = none retired.
     *
     * Stamped when the password changes, which retires every session that was
     * already open -- see V11. UTC, like every other timestamp in this schema.
     */
    @Column(name = "tokens_valid_from")
    private LocalDateTime tokensValidFrom;

    @PrePersist
    void onCreate() {
        // UTC, because the column is DATETIME and MySQL has no timezone-aware
        // type -- the whole application agrees to store UTC and convert at the
        // edges. See the serverTimezone=UTC note in application.yaml.
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    /**
     * Whether this account may act at all.
     *
     * ALL THREE FLAGS. Each refuses for a different reason, and each has to be
     * checked because none implies the others:
     *
     *   emailVerified   they proved they own the address
     *   approvalStatus  an administrator let them in (staff)
     *   is_active       nobody has since switched the account off
     *
     * Deliberately separate from the role check. "Is this account switched on"
     * and "may this kind of account do X" are different questions, and
     * answering them in one place is how one of them ends up skipped.
     */
    public boolean isEnabled() {
        return active && emailVerified && approvalStatus == ApprovalStatus.APPROVED;
    }

    /** True when this user is staff at the given shelter. Null-safe. */
    public boolean belongsToShelter(Long otherShelterId) {
        return shelterId != null && shelterId.equals(otherShelterId);
    }
}
