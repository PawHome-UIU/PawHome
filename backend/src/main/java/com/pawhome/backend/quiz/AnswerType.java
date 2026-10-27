package com.pawhome.backend.quiz;

/**
 * Maps `quiz_questions.answer_type`.
 *
 * Every one of these is stored the same way -- as rows in quiz_options -- and
 * the type says how many may be chosen and who writes the labels:
 *
 *   YES_NO         exactly two options, and the service writes them. Nobody
 *                  should be able to author a yes/no question whose choices
 *                  are "Yes" and "Maybe".
 *   SINGLE_CHOICE  one of many, labels authored.
 *   MULTI_CHOICE   any number of many, labels authored.
 *   SCALE          an ordered run of points; the service writes the labels
 *                  from the count, so the rungs are evenly spaced and in order.
 *
 * There is no "free text" member, and that is deliberate: an answer nobody can
 * score cannot move an eligibility total or map to a trait. A follow-up in the
 * adopter's own words hangs off an OPTION instead, through
 * quiz_options.requires_text -- so the choice is still scoreable and the prose
 * rides along with it.
 */
public enum AnswerType {

    YES_NO,
    SINGLE_CHOICE,
    MULTI_CHOICE,
    SCALE;

    /** True when the author supplies the labels rather than the service. */
    public boolean isAuthored() {
        return this == SINGLE_CHOICE || this == MULTI_CHOICE;
    }
}
