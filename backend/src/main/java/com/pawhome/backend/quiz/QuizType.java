package com.pawhome.backend.quiz;

/**
 * Maps `quiz_questions.quiz_type`. Three quizzes share one bank of questions.
 *
 * The distinction that matters is not what they ask but how they are scored,
 * and V6 made that a database rule rather than a convention:
 *
 *   ELIGIBILITY  scored. Option weights are summed; the total decides whether
 *                an adoption application may proceed.
 *   PERSONALITY  NOT scored -- score, max_score and passed are NULL for these
 *                attempts, enforced by ck_attempts_scored_by_type. It produces
 *                a set of traits, and the match percentage on a recommendation
 *                is the overlap between those and the pet's own.
 *   BOARDING     scored, and asked when an owner requests a stay.
 *
 * The names are identical to the SQL labels: @Enumerated(STRING) writes the
 * constant name straight into the column.
 */
public enum QuizType { ELIGIBILITY, PERSONALITY, BOARDING }
