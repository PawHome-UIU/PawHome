package com.pawhome.backend.mail;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Emails adopters when a pet's vaccination is due or overdue.
 *
 * Runs nightly. Two passes over the same query shape:
 *
 *   DUE_SOON  next_due_on within the next 14 days
 *   OVERDUE   next_due_on already past
 *
 * IDEMPOTENT, WHICH IS THE WHOLE DIFFICULTY. Without health_reminders_sent this
 * job mails the same person about the same booster every night until the shot
 * is recorded -- which for an overdue vaccination could be months. The unique
 * key on (vaccination_id, reminder_kind) means each pass fires once per shot,
 * and the INSERT is what claims it.
 *
 * ONLY ADOPTED PETS. pets.owner_id is NULL until an adoption completes, so an
 * INNER JOIN through it naturally excludes shelter residents -- their
 * vaccinations are staff's job and appear on the Care &amp; Health page instead.
 */
@Component
@RequiredArgsConstructor
public class HealthReminderJob {

    private static final Logger log = LoggerFactory.getLogger(HealthReminderJob.class);

    private static final int DUE_SOON_DAYS = 14;

    private final JdbcTemplate jdbc;
    private final MailService mailService;

    @Value("${app.mail.reminders-enabled:true}")
    private boolean enabled;

    /**
     * 07:00 UTC daily.
     *
     * A fixed hour rather than fixedRate, so restarting the app during the day
     * does not fire a fresh round of emails. Anyone who has deployed on a busy
     * afternoon and mailed everyone twice knows why.
     */
    @Scheduled(cron = "${app.mail.reminder-cron:0 0 7 * * *}", zone = "UTC")
    @Transactional
    public void sendDueReminders() {
        if (!enabled) {
            log.debug("Health reminders are switched off.");
            return;
        }
        int soon = run(false);
        int overdue = run(true);
        if (soon + overdue > 0) {
            log.info("Health reminders: {} due soon, {} overdue", soon, overdue);
        }
    }

    private int run(boolean overdue) {
        String kind = overdue ? "OVERDUE" : "DUE_SOON";

        // Written out rather than through JPA because it spans five tables and
        // is read-only. Every value is BOUND (?), never concatenated -- the
        // only variable parts are the date window and the reminder kind.
        String sql = """
            SELECT  v.id            AS vaccination_id,
                    p.name          AS pet_name,
                    u.full_name     AS owner_name,
                    u.email         AS owner_email,
                    vt.name         AS vaccine_name,
                    v.next_due_on   AS due_on,
                    s.name          AS shelter_name
            FROM        vaccinations  v
            JOIN        pets          p  ON p.id  = v.pet_id
            -- INNER JOIN through owner_id: a pet with no owner is still in the
            -- shelter, and there is nobody to email about it.
            JOIN        users         u  ON u.id  = p.owner_id
            JOIN        vaccine_types vt ON vt.id = v.vaccine_type_id
            LEFT JOIN   shelters      s  ON s.id  = p.shelter_id
            WHERE   v.next_due_on IS NOT NULL
              AND   v.status = 'SCHEDULED'
              AND   %s
              -- Only people who confirmed their address and are still active.
              AND   u.email_verified = TRUE
              AND   u.is_active      = TRUE
              -- V10's switches, and this is the one that makes them real.
              -- notify_health is the topic; notify_email is the channel, so
              -- turning either off stops this mail -- and the settings page
              -- stops being a row of switches wired to nothing.
              AND   u.notify_health  = TRUE
              AND   u.notify_email   = TRUE
              -- The idempotence guard: skip anything already sent.
              AND   NOT EXISTS (SELECT 1 FROM health_reminders_sent r
                                WHERE r.vaccination_id = v.id
                                  AND r.reminder_kind  = ?)
            """.formatted(overdue
                ? "v.next_due_on < CURRENT_DATE"
                : "v.next_due_on BETWEEN CURRENT_DATE AND DATE_ADD(CURRENT_DATE, INTERVAL ? DAY)");

        List<Map<String, Object>> rows = overdue
                ? jdbc.queryForList(sql, kind)
                : jdbc.queryForList(sql, DUE_SOON_DAYS, kind);

        int sent = 0;
        for (Map<String, Object> row : rows) {
            Long vaccinationId = ((Number) row.get("vaccination_id")).longValue();
            String email = (String) row.get("owner_email");

            // Claim it BEFORE sending. If the insert loses a race with another
            // instance, the unique key rejects it and this one skips -- better
            // to occasionally miss than to send twice.
            int claimed = jdbc.update("""
                INSERT IGNORE INTO health_reminders_sent
                    (vaccination_id, reminder_kind, sent_to)
                VALUES (?, ?, ?)
                """, vaccinationId, kind, email);

            if (claimed == 0) continue;

            mailService.sendHealthReminder(
                    email,
                    (String) row.get("owner_name"),
                    (String) row.get("pet_name"),
                    (String) row.get("vaccine_name"),
                    String.valueOf(row.get("due_on")),
                    overdue,
                    // NULL since V9 means a household pet -- no shelter has
                    // ever had it, so "your shelter" would name a thing that
                    // does not exist for this reader.
                    row.get("shelter_name") == null
                            ? "your vet" : (String) row.get("shelter_name"));
            sent++;
        }
        return sent;
    }
}
