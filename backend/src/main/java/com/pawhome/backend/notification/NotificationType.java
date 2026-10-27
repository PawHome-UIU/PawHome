package com.pawhome.backend.notification;

/**
 * Maps the `notifications.type` ENUM in V1__init.sql.
 *
 * SEVEN VALUES, AND ALL SEVEN NOW HAVE CODE BEHIND THEM. CARE_TASK_DUE sat
 * here unraised from V1 until V12 gave it the guard column its sweep needed;
 * SLIP_ISSUED is the newest, added by V13.
 *
 * THIS LIST AND THE COLUMN MUST AGREE. notifications.type is a MySQL ENUM, so
 * a value added here without a migration is an INSERT that fails at runtime
 * with a data-truncation error nobody reads as "you forgot the migration".
 * V13 redeclares the column with all seven.
 */
public enum NotificationType {

    /** To the shelter's staff: somebody has applied for one of your pets. */
    APPLICATION_SUBMITTED,

    /** To the applicant: approved or declined. */
    APPLICATION_DECIDED,

    /** To the owner: their boarding request was accepted or refused. */
    BOARDING_DECIDED,

    /** To the owner: the shelter posted a note about the stay. */
    BOARDING_UPDATE,

    /** To a staff member: an admin approved or refused their account. */
    STAFF_APPROVAL,

    /** To whoever owns an overdue task, or to the whole shelter if nobody does. */
    CARE_TASK_DUE,

    /**
     * To the owner: a bill has been written.
     *
     * FOR A BOARDING SLIP this is a courtesy -- they are standing at the
     * counter collecting the animal. FOR A DAYCARE SLIP it is the only thing
     * that tells them: nobody is standing anywhere when a @Scheduled job runs
     * at 03:00 on the first of the month.
     */
    SLIP_ISSUED
}
