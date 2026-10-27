package com.pawhome.backend.quiz;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One answer option. Maps `quiz_options`.
 *
 * WEIGHT AND TRAITS ARE THE TWO SCORING MECHANISMS, and a given option uses
 * exactly one of them:
 *
 *   ELIGIBILITY and BOARDING  weight counts, traits are meaningless.
 *   PERSONALITY               traits count, and V2 seeds every weight 0.
 *
 * They live on the same row rather than in two tables because they are the
 * same fact -- what choosing this option means -- expressed for two different
 * scorers. The service is what refuses the combinations that make no sense.
 *
 * uq_option_order (question_id, display_order) is why the service always
 * deletes a question's options before writing the new set: renumbering them in
 * place collides with itself the moment two options swap positions.
 */
@Entity
@Table(name = "quiz_options")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuizOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    @Column(nullable = false, length = 120)
    private String label;

    /** Summed into the eligibility score. Zero on personality options. */
    @Column(nullable = false)
    @Builder.Default
    private short weight = 0;

    /** Opens a free-text follow-up ("Other" -> what, exactly?). */
    @Column(name = "requires_text", nullable = false)
    @Builder.Default
    private boolean requiresText = false;

    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private int displayOrder = 0;
}
