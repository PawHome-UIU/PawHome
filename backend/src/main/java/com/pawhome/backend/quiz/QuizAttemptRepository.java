package com.pawhome.backend.quiz;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface QuizAttemptRepository extends JpaRepository<QuizAttempt, Long> {

    /**
     * The most recent sitting, which is the one that counts.
     *
     * ORDER BY id DESC LIMIT 1 is what the derived name's "First" produced.
     * Retaking a quiz REPLACES the previous answer set rather than adding to
     * it, and this single line is the whole of that rule.
     */
    @Query(value = """
           SELECT *
             FROM quiz_attempts
            WHERE user_id = :userId
              AND quiz_type = :#{#quizType.name()}
            ORDER BY id DESC
            LIMIT 1
           """, nativeQuery = true)
    Optional<QuizAttempt> findFirstByUserIdAndQuizTypeOrderByIdDesc(
            @Param("userId") Long userId, @Param("quizType") QuizType quizType);
}
