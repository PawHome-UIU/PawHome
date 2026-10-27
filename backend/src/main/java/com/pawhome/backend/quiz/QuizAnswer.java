package com.pawhome.backend.quiz;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One answer within an attempt. Maps `quiz_answers`.
 *
 * THREE SHAPES IN ONE TABLE, and the nullable columns are how:
 *
 *   a chosen option   option_id set, scale_value null
 *   a scale answer    option_id NULL, scale_value 1..5 (ck_answers_scale)
 *   a follow-up       answer_text beside either, when the option asked for one
 *
 * A MULTI_CHOICE question therefore writes one row per option chosen, which is
 * why uq_answer is (attempt_id, question_id, option_id) and not just the first
 * two: the same question legitimately appears several times in one attempt.
 */
@Entity
@Table(name = "quiz_answers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuizAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "attempt_id", nullable = false)
    private Long attemptId;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    /** Null on a scale answer. */
    @Column(name = "option_id")
    private Long optionId;

    @Column(name = "answer_text", columnDefinition = "TEXT")
    private String answerText;

    /** 1..5, and only on a SCALE question -- ck_answers_scale. */
    @Column(name = "scale_value")
    private Short scaleValue;
}
