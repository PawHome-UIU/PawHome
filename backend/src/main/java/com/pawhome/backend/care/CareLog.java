package com.pawhome.backend.care;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * What was actually done. Maps `care_logs`.
 *
 * TWO TABLES AND NOT ONE, which V1 argues for where it declares them: "a
 * scheduled task has a due time, an assignee and a done/not-done state; a log
 * has an actual time, an author and a note. They have different lifecycles --
 * a task is updated, a log is never touched again."
 *
 * task_id IS NULLABLE, and ON DELETE SET NULL rather than CASCADE. An ad-hoc
 * note -- "found him limping, called the vet" -- answers to no task at all,
 * and a log written against a task that is later deleted still records
 * something that happened. Losing it with the schedule would be losing the
 * history to tidy up the plan.
 */
@Entity
@Table(name = "care_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CareLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pet_id", nullable = false)
    private Long petId;

    @Column(name = "boarding_id")
    private Long boardingId;

    /** NULL for an ad-hoc entry that answers to no scheduled task. */
    @Column(name = "task_id")
    private Long taskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false)
    private CareTaskType taskType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String notes;

    @Column(name = "logged_by", nullable = false)
    private Long loggedBy;

    @Column(name = "logged_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime loggedAt;
}
