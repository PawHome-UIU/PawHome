-- =============================================================================
-- V12__care_task_due_notified.sql
--
-- ONE COLUMN, SO A SCHEDULED SWEEP CANNOT SHOUT TWICE
-- --------------------------------------------------
-- CARE_TASK_DUE is the sixth value of the notifications.type ENUM and the only
-- one nothing could ever send, because care_tasks had no code behind it. The
-- job that fills that gap runs on a timer and asks the same question every
-- time -- "which pending tasks are past their due_at?" -- and a task stays
-- past its due date until somebody does it. Without a marker the assignee
-- would be told about the same task on every single run, which is how people
-- learn to ignore a notification.
--
-- THE SAME PROBLEM HealthReminderJob ALREADY SOLVED, and deliberately not the
-- same way. That job writes to health_reminders_sent, a whole table, because
-- one vaccination can produce TWO different reminders -- due-soon and overdue
-- -- so the key it needs is (vaccination, kind) and a single timestamp on the
-- row could not express it. A care task has exactly one thing to say, once, so
-- one nullable column on the task carries it and there is no second table to
-- join, sweep or keep in step.
--
-- NULLABLE, WITH NO DEFAULT: NULL means "nobody has been told yet", which is
-- true of every row that exists. Filling it with CURRENT_TIMESTAMP would mark
-- the whole backlog as already announced.
--
-- CLEARED WHEN A TASK IS RESCHEDULED. A due date moved forward is a new
-- deadline and deserves a new reminder, so CareService sets this back to NULL
-- whenever due_at changes -- the column records "told about THIS deadline",
-- not "told about this row once, ever".
-- =============================================================================

ALTER TABLE care_tasks
    ADD COLUMN due_notified_at DATETIME NULL
        COMMENT 'When the assignee was told this task is due. NULL = not yet.';
