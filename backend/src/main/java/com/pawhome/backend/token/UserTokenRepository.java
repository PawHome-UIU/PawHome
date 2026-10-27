package com.pawhome.backend.token;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface UserTokenRepository extends JpaRepository<UserToken, Long> {

    /**
     * The only read path: the caller hashes what they were given and looks it up.
     *
     * One unique-index lookup on uq_user_tokens_hash, however many tokens the
     * table holds. The raw token is never stored, so this is also the reason a
     * stolen database dump yields no working reset links.
     */
    @Query(value = """
           SELECT *
             FROM user_tokens
            WHERE token_hash = :tokenHash
           """, nativeQuery = true)
    Optional<UserToken> findByTokenHash(@Param("tokenHash") String tokenHash);

    /**
     * Invalidate a user's outstanding links of one kind.
     *
     * Called before issuing a new one, so requesting a second reset email makes
     * the first link dead. Without this, every reset ever requested stays live
     * until it expires -- an old email in a compromised inbox is then still a
     * working key.
     *
     * Parameters are BOUND (:userId, :type), never concatenated -- this is the
     * one place in the codebase with hand-written JPQL, and it is the place a
     * future injection bug would most plausibly start.
     */
    @Modifying
    @Query("UPDATE UserToken t SET t.usedAt = :now "
         + "WHERE t.userId = :userId AND t.tokenType = :type AND t.usedAt IS NULL")
    int invalidateOutstanding(@Param("userId") Long userId,
                              @Param("type") TokenType type,
                              @Param("now") LocalDateTime now);

    /** Housekeeping: drop rows that can never be redeemed again. */
    @Modifying
    @Query("DELETE FROM UserToken t WHERE t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") LocalDateTime cutoff);
}
