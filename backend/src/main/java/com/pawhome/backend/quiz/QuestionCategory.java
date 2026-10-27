package com.pawhome.backend.quiz;

/**
 * Maps `quiz_questions.category` -- what the question is ABOUT, which is a
 * different axis from which quiz it belongs to.
 *
 * Worth stating because the admin modal used to conflate the two, offering
 * "Eligibility / Personality" under the label "Category" and never writing
 * this column at all. A question has both: an ELIGIBILITY question about the
 * applicant's home is quiz_type ELIGIBILITY, category ENVIRONMENT.
 */
public enum QuestionCategory {

    /** The home itself: space, yard, other animals, children. */
    ENVIRONMENT,

    /** Hours, activity, routine -- how the pet's day would actually go. */
    LIFESTYLE,

    /** What they have done before: previous pets, previous adoptions. */
    HISTORY,

    /** Willingness to keep going: cost, time, the long term. */
    COMMITMENT,

    /** Medical facts, mostly on the boarding quiz. */
    HEALTH
}
