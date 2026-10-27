package com.pawhome.backend.care;

import com.pawhome.backend.notification.NotificationService;
import com.pawhome.backend.notification.NotificationType;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * The sweep that finally sends CARE_TASK_DUE.
 *
 * That value has been in the notifications.type ENUM since V1 as the only one
 * nothing could raise, because care_tasks had no code behind it. This is the
 * other end of it.
 *
 * WHY A TIMER AND NOT AN EVENT. Every other notification in this system is
 * raised by somebody doing something -- an application approved, a stay
 * accepted, a note posted. A task becoming overdue is the opposite: it is
 * nobody doing anything, and there is no request to hang a notification off.
 * The only thing that can notice it is a clock.
 *
 * ONCE PER DEADLINE, WHICH IS WHAT V12's COLUMN IS FOR. A task stays past its
 * due date until somebody does it, so an unguarded sweep would tell the same
 * person about the same task on every run until they gave in -- which is how
 * people learn to ignore a bell. due_notified_at is stamped when they are
 * told, and CareService clears it when the due date moves, because a new
 * deadline earns a new reminder.
 *
 * THREADING: @Scheduled, on the pool AsyncConfig already configures for the
 * vaccination reminders. It is @Transactional so the stamp and the
 * notification rows commit together -- stamping outside the transaction could
 * mark a task announced whose notification then rolled back, and that task
 * would never be mentioned again.
 *
 * THE DEFAULT IS EVERY FIFTEEN MINUTES. A care task is measured in hours, so
 * anything tighter is load for no benefit, and anything much looser makes
 * "due" arrive long after it was.
 */
@Component
@RequiredArgsConstructor
public class CareDueJob {

    private static final Logger log = LoggerFactory.getLogger(CareDueJob.class);

    private final JdbcTemplate jdbc;
    private final NotificationService notifications;

    @Scheduled(cron = "${app.care.due-cron:0 */15 * * * *}", zone = "UTC")
    @Transactional
    public void announceDueTasks() {
        List<Map<String, Object>> due = jdbc.queryForList("""
                SELECT  t.id, t.description, t.priority, t.assigned_to,
                        p.name       AS pet_name,
                        p.shelter_id AS shelter_id
                  FROM  care_tasks t
                  JOIN  pets p ON p.id = t.pet_id
                 WHERE  t.status          = 'PENDING'
                   AND  t.due_at          < NOW()
                   -- V12: the guard that makes this run once per deadline
                   -- rather than once per sweep, for ever.
                   AND  t.due_notified_at IS NULL
                """);

        if (due.isEmpty()) return;

        for (Map<String, Object> row : due) {
            Long taskId = ((Number) row.get("id")).longValue();
            String petName = (String) row.get("pet_name");
            boolean critical = "CRITICAL".equals(row.get("priority"));
            String title = (critical ? "Overdue (critical): " : "Overdue: ") + petName;
            String body = (String) row.get("description");
            String link = "/staff/care";

            Object assignee = row.get("assigned_to");
            if (assignee != null) {
                notifications.raise(((Number) assignee).longValue(),
                        NotificationType.CARE_TASK_DUE, title, body, link);
            } else {
                // An unassigned task belongs to the whole shelter, not to
                // nobody -- so everybody there hears about it rather than the
                // reminder falling into a gap between rotas.
                Object shelterId = row.get("shelter_id");
                if (shelterId != null) {
                    notifications.raiseForShelterStaff(((Number) shelterId).longValue(),
                            NotificationType.CARE_TASK_DUE, title, body, link);
                }
            }

            jdbc.update("UPDATE care_tasks SET due_notified_at = NOW() WHERE id = ?", taskId);
        }

        log.info("Announced {} overdue care task(s)", due.size());
    }
}
