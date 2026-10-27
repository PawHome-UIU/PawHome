package com.pawhome.backend.care;

/**
 * Maps `care_tasks.status`.
 *
 * THERE IS NO OVERDUE, and V1 says so at the column: overdue is
 * `status = 'PENDING' AND due_at < NOW()`, evaluated when somebody asks.
 * Storing it would need a job whose only purpose is to keep a boolean true,
 * and the boolean would be wrong for however long that job was late.
 *
 * SKIPPED IS NOT DONE. A task nobody did is a fact about the day, and folding
 * it into DONE would make "was the dog fed" unanswerable from the record.
 */
public enum CareTaskStatus {
    PENDING,
    DONE,
    SKIPPED
}
