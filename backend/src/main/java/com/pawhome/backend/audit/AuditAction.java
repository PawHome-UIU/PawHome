package com.pawhome.backend.audit;

/**
 * The actions worth recording, as constants rather than an enum.
 *
 * NOT AN ENUM, AND THIS IS THE ONE PLACE IN THE PROJECT THAT GOES THE OTHER
 * WAY. Everywhere else a closed set becomes a Java enum backed by a MySQL ENUM
 * -- BoardingStatus, DaycareSlot, UpdateType -- because those sets are closed
 * and the code switches on them.
 *
 * This set is neither. audit_log.action is VARCHAR(60) by V1's design, nothing
 * switches on the value, and the set grows every time somebody decides another
 * event is worth keeping. Making it an enum would mean a migration per new
 * event AND would make OLD ROWS UNREADABLE the day a value was renamed -- an
 * audit trail that cannot read its own history is the one thing it must not be.
 *
 * So: constants for the writers, so a typo is a compile error rather than a row
 * nobody can find, and a plain string in the column, so the past stays legible.
 */
public final class AuditAction {

    private AuditAction() {}

    // ---- entity types ----
    public static final String PET         = "PET";
    public static final String APPLICATION = "APPLICATION";
    public static final String BOARDING    = "BOARDING";
    public static final String SHELTER     = "SHELTER";
    public static final String USER        = "USER";

    // ---- actions ----
    public static final String PET_ADDED            = "PET_ADDED";
    public static final String PET_REMOVED          = "PET_REMOVED";
    public static final String APPLICATION_APPROVED = "APPLICATION_APPROVED";
    public static final String APPLICATION_DECLINED = "APPLICATION_DECLINED";
    public static final String BOARDING_ACCEPTED    = "BOARDING_ACCEPTED";
    public static final String BOARDING_COMPLETED   = "BOARDING_COMPLETED";
    public static final String SHELTER_ADDED        = "SHELTER_ADDED";
    public static final String STAFF_APPROVED       = "STAFF_APPROVED";
    public static final String STAFF_REJECTED       = "STAFF_REJECTED";
}
