package com.pawhome.backend.quiz;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface QuizOptionRepository extends JpaRepository<QuizOption, Long> {

    @Query(value = """
           SELECT *
             FROM quiz_options
            WHERE question_id = :questionId
            ORDER BY display_order ASC
           """, nativeQuery = true)
    List<QuizOption> findByQuestionIdOrderByDisplayOrderAsc(
            @Param("questionId") Long questionId);

    /**
     * Every option for a whole quiz, in ONE round trip.
     *
     * The IN list is what avoids N+1: called per question this would be one
     * query per question, so a fourteen-question quiz would be fourteen trips
     * to the database to draw one page.
     */
    @Query(value = """
           SELECT *
             FROM quiz_options
            WHERE question_id IN (:questionIds)
            ORDER BY display_order ASC
           """, nativeQuery = true)
    List<QuizOption> findByQuestionIdInOrderByDisplayOrderAsc(
            @Param("questionIds") List<Long> questionIds);

    /**
     * Clear a question's options before writing the new set.
     *
     * option_traits cascades from here, so the trait mappings go with them.
     */
    @Modifying
    @Query("DELETE FROM QuizOption o WHERE o.questionId = :questionId")
    void deleteByQuestionId(@Param("questionId") Long questionId);
}
