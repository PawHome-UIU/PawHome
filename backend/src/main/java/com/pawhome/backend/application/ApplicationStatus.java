package com.pawhome.backend.application;

/**
 * Maps `adoption_applications.status`.
 *
 * PENDING IS THE ONE THE DATABASE TREATS SPECIALLY:
 *
 *   pending_flag TINYINT AS (IF(status = 'PENDING', 1, NULL)) STORED
 *   UNIQUE KEY uq_one_open_app (applicant_id, pet_id, pending_flag)
 *
 * A MySQL unique index ignores rows with a NULL key part, so every decided
 * application is exempt while one person can have only one OPEN application
 * for one pet. Which means re-applying after a refusal is allowed, and
 * double-clicking submit is not.
 */
public enum ApplicationStatus {

    /** Waiting for a shelter to decide. At most one per applicant per pet. */
    PENDING,

    /** Accepted. An adoption row exists, and the pet has an owner. */
    APPROVED,

    /** Refused, with a reason code and a written message -- ck_apps_decline. */
    REJECTED,

    /** Taken back by the applicant before anyone decided. */
    WITHDRAWN
}
