package com.pawhome.backend.application;

import jakarta.persistence.*;
import org.hibernate.annotations.DynamicUpdate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One application to adopt one pet. Maps `adoption_applications`.
 *
 * WHY THE APPLICANT'S DETAILS ARE COPIED HERE rather than read from users: an
 * application is a record of what somebody said AT THE TIME. They move house
 * six months later and the application that was approved on the strength of a
 * house with a yard must not quietly become an application from a flat. The
 * same argument covers quiz_score, which is the mark this application was
 * judged on even if the bank's weights change afterwards.
 *
 * eligibility_quiz_type IS A CONSTANT, and V6 says so in the column comment.
 * It exists only so the foreign key can be composite:
 *
 *   FOREIGN KEY (eligibility_attempt_id, eligibility_quiz_type)
 *       REFERENCES quiz_attempts (id, quiz_type)
 *
 * with CHECK (eligibility_quiz_type = 'ELIGIBILITY'). That pair is what makes
 * it impossible to attach a personality or boarding attempt to an adoption
 * application -- a plain foreign key on the id alone could not tell them apart.
 * Not mapped as a field: nothing may ever set it to anything else.
 *
 * WHY @DynamicUpdate.
 *
 * Without it Hibernate uses ONE cached UPDATE statement per entity, listing
 * every column, whatever actually changed. Approving an application changes
 * three fields -- status, reviewed_by, reviewed_at -- and the statement it
 * sent was:
 *
 *     update adoption_applications set applicant_address=?, applicant_city=?,
 *       applicant_email=?, applicant_id=?, applicant_name=?, applicant_phone=?,
 *       decline_message=?, decline_reason_code=?, eligibility_attempt_id=?,
 *       home_type=?, household_note=?, pet_experience=?, pet_id=?,
 *       quiz_score=?, reviewed_at=?, reviewed_by=?, status=?, time_at_home=?
 *      where id=?
 *
 * Eighteen columns rewritten to change three. It is correct but wasteful, and
 * it rewrites the applicant's own copied answers on every staff decision --
 * which is how a last-write-wins bug appears the day two people act at once.
 *
 * @DynamicUpdate makes Hibernate build the statement from the fields that are
 * actually dirty, so the same approval now sends:
 *
 *     update adoption_applications set reviewed_at=?, reviewed_by=?, status=?
 *      where id=?
 *
 * THE COST is that the statement can no longer be prepared once and reused --
 * Hibernate builds it per flush. On a table this wide, with this few writes,
 * that trade is obviously worth it. On a hot table with one changing column
 * it would not be.
 */
@DynamicUpdate
@Entity
@Table(name = "adoption_applications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdoptionApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pet_id", nullable = false)
    private Long petId;

    @Column(name = "applicant_id", nullable = false)
    private Long applicantId;

    @Column(name = "eligibility_attempt_id", nullable = false)
    private Long eligibilityAttemptId;

    /** ck_apps_score: >= 0. Copied from the attempt, not read back from it. */
    @Column(name = "quiz_score", nullable = false)
    private short quizScore;

    @Column(name = "applicant_name", nullable = false, length = 120)
    private String applicantName;

    @Column(name = "applicant_email", nullable = false, length = 160)
    private String applicantEmail;

    @Column(name = "applicant_phone", nullable = false, length = 30)
    private String applicantPhone;

    @Column(name = "applicant_address", nullable = false, length = 200)
    private String applicantAddress;

    @Column(name = "applicant_city", nullable = false, length = 80)
    private String applicantCity;

    @Column(name = "home_type", nullable = false, length = 60)
    private String homeType;

    @Column(name = "time_at_home", nullable = false, length = 60)
    private String timeAtHome;

    @Column(name = "pet_experience", nullable = false, length = 200)
    private String petExperience;

    @Column(name = "household_note", length = 200)
    private String householdNote;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ApplicationStatus status = ApplicationStatus.PENDING;

    @Column(name = "submitted_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime submittedAt;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "decline_reason_code")
    private DeclineReason declineReasonCode;

    @Column(name = "decline_message", columnDefinition = "TEXT")
    private String declineMessage;
}
