package com.pawhome.backend.quiz;

/**
 * Maps `quiz_questions.status`.
 *
 * THERE IS NO DELETE, and the reason is in V1's own comment on the table:
 * quiz_answers reference a question forever. Removing one would either destroy
 * the answers somebody already gave -- which is the evidence behind an approved
 * adoption -- or leave rows pointing at nothing. ARCHIVED is how a question
 * stops being asked while every attempt that used it stays readable.
 */
public enum QuestionStatus {

    /** Being written. Never served to anyone taking the quiz. */
    DRAFT,

    /** Asked. */
    ACTIVE,

    /** Retired. Not asked again; still referenced by past answers. */
    ARCHIVED
}
