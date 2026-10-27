package com.pawhome.backend.care;

/**
 * Maps the task_type ENUM shared by `care_tasks` and `care_logs`.
 *
 * ONE ENUM FOR BOTH TABLES, because a log is what happened when a task was
 * done and the two have to line up: a FEEDING task completed with a GROOMING
 * log would be a record nobody could read. V1 declares the same five values in
 * both columns for that reason.
 */
public enum CareTaskType {
    FEEDING,
    MEDICATION,
    EXERCISE,
    GROOMING,
    WOUND_CARE
}
