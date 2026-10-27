package com.pawhome.backend.quiz;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One question. Maps `quiz_questions` from V1__init.sql.
 *
 * NO @OneToMany TO quiz_options, following the project's rule that foreign
 * keys stay as ids and the service resolves them. Here it also avoids a
 * specific trap: the admin list shows an option COUNT per question, and a
 * mapped collection would make that a query per row -- fourteen questions,
 * fifteen queries -- for a number one GROUP BY already has.
 */
@Entity
@Table(name = "quiz_questions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuizQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "quiz_type", nullable = false)
    private QuizType quizType;

    @Column(name = "question_text", nullable = false, columnDefinition = "TEXT")
    private String questionText;

    @Enumerated(EnumType.STRING)
    @Column(name = "answer_type", nullable = false)
    private AnswerType answerType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QuestionCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private QuestionStatus status = QuestionStatus.DRAFT;

    @Column(name = "is_required", nullable = false)
    @Builder.Default
    private boolean required = true;

    /**
     * Position within its quiz. Not unique in the schema, deliberately: two
     * questions may briefly share an order while a reorder is being applied,
     * and a unique key would make every reorder a puzzle about which row to
     * move first.
     */
    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private int displayOrder = 0;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    /** Set by the column DEFAULT, so read-only from Java. */
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
