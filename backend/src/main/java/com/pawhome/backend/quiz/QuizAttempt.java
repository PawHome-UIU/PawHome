package com.pawhome.backend.quiz;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One sitting of one quiz. Maps `quiz_attempts`.
 *
 * THE THREE SCORE COLUMNS ARE NULLABLE, AND THAT IS A RULE, NOT A CONVENIENCE.
 * V6 added:
 *
 *   CONSTRAINT ck_attempts_scored_by_type CHECK (
 *       (quiz_type =  'PERSONALITY' AND score IS NULL AND max_score IS NULL
 *                                   AND passed IS NULL) OR
 *       (quiz_type <> 'PERSONALITY' AND score IS NOT NULL AND max_score IS NOT NULL
 *                                   AND passed IS NOT NULL))
 *
 * A personality quiz has no mark. It produces a set of traits, and writing a 0
 * there would put a number on a screen that means nothing and invites somebody
 * to compare it. Boxed types rather than primitives for exactly that reason: a
 * primitive cannot be absent.
 *
 * SHORT, NOT INTEGER. Both columns are SMALLINT, and ddl-auto is `validate`, so
 * mapping them as Integer does not fail quietly at runtime -- it refuses to
 * start:
 *
 *   Schema-validation: wrong column type encountered in column [max_score]
 *   in table [quiz_attempts]; found [smallint], but expecting [integer]
 *
 * which is the validator doing its job. The scores are converted to int at the
 * DTO boundary, where a caller should not have to care how wide the column is.
 */
@Entity
@Table(name = "quiz_attempts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuizAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "quiz_type", nullable = false)
    private QuizType quizType;

    /** SMALLINT. ck_attempts_score: 0 <= score <= max_score. */
    @Column
    private Short score;

    @Column(name = "max_score")
    private Short maxScore;

    @Column
    private Boolean passed;

    @Column(name = "submitted_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime submittedAt;
}
