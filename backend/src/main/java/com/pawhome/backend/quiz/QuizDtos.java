package com.pawhome.backend.quiz;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class QuizDtos {

    private QuizDtos() {}

    // ------------------------------------------------------------- reading

    /**
     * One row of the admin table.
     *
     * `optionCount` and `answeredCount` come from one GROUP BY, not a query per
     * row. answeredCount is the one that changes what the editor may do: an
     * option somebody has already chosen cannot be removed (fk_answers_option
     * is ON DELETE RESTRICT), so the UI needs to know before it offers.
     */
    public record QuestionRow(
            Long id,
            QuizType quizType,
            String questionText,
            AnswerType answerType,
            QuestionCategory category,
            QuestionStatus status,
            boolean required,
            int displayOrder,
            int optionCount,
            long answeredCount,
            LocalDateTime createdAt
    ) {}

    /** An option as the editor sees it, with the traits it maps to. */
    public record OptionRow(
            Long id,
            String label,
            short weight,
            boolean requiresText,
            int displayOrder,
            List<Long> traitIds,
            /** Chosen at least once, so it can no longer be deleted. */
            boolean inUse
    ) {}

    /** A question opened for editing. */
    public record QuestionDetail(QuestionRow question, List<OptionRow> options) {}

    /** A temperament chip, for the editor's picker. */
    public record TraitRow(Long id, String label, String slug) {}

    /** Counts for the page's tiles, in one round trip. */
    public record QuizCounts(long total, long eligibility, long personality,
                             long boarding, long active) {}

    // ------------------------------------------------------------- writing

    /**
     * One option as submitted.
     *
     * `id` present means "this is the existing option, edited". Absent means a
     * new one. That distinction is not cosmetic: keeping the id is what lets an
     * option that has already been answered survive an edit at all.
     *
     * The label is optional because for YES_NO and SCALE the SERVICE writes it
     * -- see QuizService.optionsFor. Nobody should be able to author a yes/no
     * question whose choices are "Yes" and "Maybe".
     */
    public record OptionRequest(
            Long id,
            @Size(max = 120) String label,
            short weight,
            boolean requiresText,
            List<Long> traitIds
    ) {}

    public record QuestionRequest(
            @NotNull QuizType quizType,
            @NotBlank(message = "Write the question.")
            @Size(max = 2000) String questionText,
            @NotNull AnswerType answerType,
            @NotNull(message = "Choose what the question is about.")
            QuestionCategory category,
            boolean required,
            /**
             * DRAFT or ACTIVE. ARCHIVED is not reachable here -- retiring a
             * question is its own action, and doing it through the same form
             * that edits the wording invites doing it by accident.
             */
            @NotNull QuestionStatus status,
            @Valid List<OptionRequest> options
    ) {}

    public record StatusRequest(@NotNull QuestionStatus status) {}

    // ------------------------------------------------------ taking the quiz

    /**
     * One option AS THE PERSON TAKING THE QUIZ SEES IT.
     *
     * NO WEIGHT AND NO TRAITS, and that is the entire reason this record exists
     * beside OptionRow rather than reusing it. The weight is the answer key:
     * anyone who can read it knows which option scores 20 and which scores 4,
     * and an eligibility score decides whether they may adopt. Withholding it
     * in the React component while the API still ships it is a gate anyone
     * walks around with devtools.
     */
    public record TakeOptionRow(Long id, String label, boolean requiresText) {}

    public record TakeQuestionRow(
            Long id,
            String questionText,
            AnswerType answerType,
            QuestionCategory category,
            boolean required,
            List<TakeOptionRow> options
    ) {}

    /** One submitted answer. */
    public record AnswerRequest(
            @NotNull Long questionId,
            /** Chosen options. Empty for a SCALE question. */
            List<Long> optionIds,
            /** 1..5, and only for a SCALE question -- ck_answers_scale. */
            @Min(1) @Max(5) Integer scaleValue,
            /** The free-text follow-up an option asked for. */
            @Size(max = 2000) String answerText
    ) {}

    public record AttemptRequest(@NotEmpty(message = "Answer the questions first.")
                                 @Valid List<AnswerRequest> answers) {}

    /**
     * What an attempt produced.
     *
     * score, maxScore and passed are ALL NULL for a personality attempt --
     * ck_attempts_scored_by_type enforces exactly that, because a personality
     * quiz produces a set of traits and not a mark out of anything.
     */
    public record AttemptRow(
            Long id,
            QuizType quizType,
            Integer score,
            Integer maxScore,
            Boolean passed,
            LocalDateTime submittedAt
    ) {}

    /** The new order, as ids. Every question in the quiz must appear once. */
    public record ReorderRequest(
            @NotEmpty(message = "Send the questions in their new order.")
            List<Long> questionIds
    ) {}
}
