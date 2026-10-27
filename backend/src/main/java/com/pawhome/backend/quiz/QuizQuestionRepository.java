package com.pawhome.backend.quiz;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface QuizQuestionRepository extends JpaRepository<QuizQuestion, Long> {

    /** One quiz's questions, in the order an admin arranged them. */
    @Query(value = """
           SELECT *
             FROM quiz_questions
            WHERE quiz_type = :#{#quizType.name()}
            ORDER BY display_order ASC
           """, nativeQuery = true)
    List<QuizQuestion> findByQuizTypeOrderByDisplayOrderAsc(
            @Param("quizType") QuizType quizType);

    @Query(value = """
           SELECT COUNT(*)
             FROM quiz_questions
            WHERE quiz_type = :#{#quizType.name()}
           """, nativeQuery = true)
    long countByQuizType(@Param("quizType") QuizType quizType);

    @Query(value = """
           SELECT COUNT(*)
             FROM quiz_questions
            WHERE quiz_type = :#{#quizType.name()}
              AND status = :#{#status.name()}
           """, nativeQuery = true)
    long countByQuizTypeAndStatus(@Param("quizType") QuizType quizType,
                                  @Param("status") QuestionStatus status);
}
