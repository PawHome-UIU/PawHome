package com.pawhome.backend.application;

/**
 * Maps `adoption_applications.decline_reason_code`.
 *
 * A CODE AND A MESSAGE, NOT ONE OR THE OTHER. ck_apps_decline requires both on
 * a rejection, and they do different jobs: the code is countable, so a shelter
 * can see that a third of its refusals are HOUSING_UNSUITABLE, while the
 * message is the sentence the person actually reads. Free text alone cannot be
 * counted; a code alone is a refusal with no explanation, which is the thing
 * people complain about.
 */
public enum DeclineReason {

    INCOMPLETE_INFO,
    HOUSING_UNSUITABLE,
    INSUFFICIENT_EXPERIENCE,

    /**
     * Written by the system, not usually chosen by hand: when one application
     * is approved every other open one for that pet is refused with this.
     */
    PET_ALREADY_MATCHED,

    FAILED_ELIGIBILITY,
    OTHER
}
