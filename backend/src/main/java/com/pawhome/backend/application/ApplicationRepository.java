package com.pawhome.backend.application;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ApplicationRepository extends JpaRepository<AdoptionApplication, Long> {

    /**
     * The other open applications for a pet, once one of them is approved.
     *
     * :#{#status.name()} RATHER THAN :status. adoption_applications.status is
     * an ENUM column and the entity maps it @Enumerated(STRING), so the value
     * stored is 'PENDING'. A native query binds a Java enum by its ORDINAL
     * unless told otherwise, which would compare 0 against 'PENDING' and match
     * nothing. SpEL sends the name, which is what the column holds.
     */
    @Query(value = """
           SELECT *
             FROM adoption_applications
            WHERE pet_id = :petId
              AND status = :#{#status.name()}
           """, nativeQuery = true)
    List<AdoptionApplication> findByPetIdAndStatus(@Param("petId") Long petId,
                                                   @Param("status") ApplicationStatus status);

    /**
     * Does this person already have an open application for this pet?
     *
     * JPQL, NOT NATIVE, AND DELIBERATELY. COUNT(a) > 0 in JPQL returns a real
     * Boolean. The native equivalent --
     *
     *     SELECT EXISTS (SELECT 1 FROM adoption_applications
     *                     WHERE applicant_id = ? AND pet_id = ? AND status = ?)
     *
     * -- returns MySQL's BIGINT 0/1, and mapping that back onto a `boolean`
     * return type relies on a converter this project does not register.
     *
     * This is the readable half of uq_one_open_app. The constraint is the
     * enforcing half: two submissions racing both pass this check and the
     * second still loses at the unique key.
     */
    @Query("""
           SELECT COUNT(a) > 0
             FROM AdoptionApplication a
            WHERE a.applicantId = :applicantId
              AND a.petId = :petId
              AND a.status = :status
           """)
    boolean existsByApplicantIdAndPetIdAndStatus(@Param("applicantId") Long applicantId,
                                                 @Param("petId") Long petId,
                                                 @Param("status") ApplicationStatus status);


    // ======================================================================
    //  THE WRITES.
    //
    //  Everything below changes rows, and every one of them is a statement
    //  written out in full rather than one Hibernate assembles.
    //
    //  WHY WRITE THEM BY HAND AT ALL. The usual way to change a row through
    //  JPA is to load the entity, call a setter, and let the transaction
    //  commit -- Hibernate notices the field is different from what it read
    //  and emits an UPDATE. That works, but it means the UPDATE exists
    //  nowhere in the source: you cannot read it, review it, or check it
    //  against an index, and the only way to see it is to switch on a
    //  logger and click the button. It also rewrites columns nobody touched
    //  (the log from an approval showed Hibernate setting all 18 columns of
    //  this table to change 3 of them).
    //
    //  A @Modifying @Query is the opposite: the exact statement is in the
    //  file, it names only the columns that change, and one call is one
    //  round trip.
    //
    //  WHAT THE TWO FLAGS ARE FOR. A @Modifying query goes straight to the
    //  database and the persistence context never hears about it, which is
    //  exactly the trap:
    //
    //    flushAutomatically = true  -- write out anything already dirty
    //                                  FIRST, so a pending change cannot
    //                                  land on top of this statement.
    //    clearAutomatically = true  -- forget the loaded copies AFTER, so
    //                                  nothing in memory still claims the
    //                                  old value and flushes it back at
    //                                  commit.
    //
    //  WHAT IS *NOT* IN THESE STATEMENTS. updated_at. The column is
    //  declared ON UPDATE CURRENT_TIMESTAMP, so MySQL maintains it on every
    //  one of these, and the entity marks it updatable = false to say so.
    // ======================================================================

    /**
     * Approve one application. Statement 1 of 4 in ApplicationService.approve.
     *
     * NOTE THE SECOND PREDICATE. `AND status = 'PENDING'` is not decoration:
     * it makes this a compare-and-set. Two reviewers with the same queue open
     * both pass the check in Java, and then exactly one of them matches a row
     * here -- the loser updates nothing and gets back 0, which the service
     * turns into a 409 instead of a silently overwritten decision.
     *
     * @return 1 if this call was the one that decided it, 0 if it was not.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
           UPDATE adoption_applications
              SET status      = 'APPROVED',
                  reviewed_by = :reviewerId,
                  reviewed_at = :decidedAt
            WHERE id     = :id
              AND status = 'PENDING'
           """, nativeQuery = true)
    int markApproved(@Param("id") Long id,
                     @Param("reviewerId") Long reviewerId,
                     @Param("decidedAt") LocalDateTime decidedAt);

    /**
     * Decline one application, with the reason the reviewer typed.
     *
     * All five columns move together because ck_apps_decline requires it: a
     * row may not be REJECTED unless decline_reason_code AND decline_message
     * are both present. Setting the status in one statement and the reason in
     * another would fail on the first of them.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
           UPDATE adoption_applications
              SET status              = 'REJECTED',
                  reviewed_by         = :reviewerId,
                  reviewed_at         = :decidedAt,
                  decline_reason_code = :#{#reasonCode.name()},
                  decline_message     = :message
            WHERE id     = :id
              AND status = 'PENDING'
           """, nativeQuery = true)
    int markDeclined(@Param("id") Long id,
                     @Param("reviewerId") Long reviewerId,
                     @Param("decidedAt") LocalDateTime decidedAt,
                     @Param("reasonCode") DeclineReason reasonCode,
                     @Param("message") String message);

    /**
     * Take an application back. The adopter's own action, so no reviewer.
     *
     * reviewed_by stays NULL on purpose -- fk_apps_reviewer points at users
     * and every screen renders that column as "who decided", so writing the
     * applicant's own id there would put their name in the shelter's reviewer
     * column. Nobody reviewed this one; it just stopped being open.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
           UPDATE adoption_applications
              SET status      = 'WITHDRAWN',
                  reviewed_at = :withdrawnAt
            WHERE id     = :id
              AND status = 'PENDING'
           """, nativeQuery = true)
    int markWithdrawn(@Param("id") Long id,
                      @Param("withdrawnAt") LocalDateTime withdrawnAt);

    /**
     * Close every OTHER open application for a pet that has just been placed.
     * Statement 4 of 4 in ApplicationService.approve.
     *
     * THIS ONE IS THE POINT OF THE EXERCISE. Done through entities it is a
     * loop: load the list, set five fields on each, and let Hibernate emit one
     * UPDATE per row -- five rival applicants means five statements and five
     * round trips. Here the same work is one statement the database does in
     * one pass, however many rows match.
     *
     * decline_reason_code is a literal rather than a parameter because this
     * path only ever has one reason: the pet went to somebody else.
     *
     * @param keepId the application being approved -- excluded, since it has
     *               already been set to APPROVED by markApproved and would
     *               otherwise be declined by its own approval.
     * @return how many rival applications were closed.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
           UPDATE adoption_applications
              SET status              = 'REJECTED',
                  reviewed_by         = :reviewerId,
                  reviewed_at         = :decidedAt,
                  decline_reason_code = 'PET_ALREADY_MATCHED',
                  decline_message     = :message
            WHERE pet_id = :petId
              AND id    <> :keepId
              AND status = 'PENDING'
           """, nativeQuery = true)
    int closeRivalApplications(@Param("petId") Long petId,
                               @Param("keepId") Long keepId,
                               @Param("reviewerId") Long reviewerId,
                               @Param("decidedAt") LocalDateTime decidedAt,
                               @Param("message") String message);
}
