package com.pawhome.backend.care;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Something that has to be done to an animal by a time. Maps `care_tasks`.
 *
 * boarding_id IS NULLABLE AND THE NULL IS THE POINT: V1's comment reads "NULL
 * for a shelter resident". The same table carries the shelter's own routine and
 * the care somebody paid for while their pet stays, because they are the same
 * work done by the same people -- and splitting them would mean two rotas to
 * read on the same morning.
 *
 * ck_tasks_done: DONE requires both completed_by and completed_at. A task
 * marked done by nobody at no time is a tick with nothing behind it, and the
 * database refuses to store one.
 */
@Entity
@Table(name = "care_tasks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CareTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pet_id", nullable = false)
    private Long petId;

    /** NULL for a shelter resident; set while the pet is boarding. */
    @Column(name = "boarding_id")
    private Long boardingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false)
    private CareTaskType taskType;

    @Column(nullable = false, length = 200)
    private String description;

    @Column(name = "due_at", nullable = false)
    private LocalDateTime dueAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private CarePriority priority = CarePriority.NORMAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private CareTaskStatus status = CareTaskStatus.PENDING;

    /** NULL means the whole shelter's, not nobody's. */
    @Column(name = "assigned_to")
    private Long assignedTo;

    @Column(name = "completed_by")
    private Long completedBy;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** V12. NULL = the assignee has not been told this deadline passed. */
    @Column(name = "due_notified_at")
    private LocalDateTime dueNotifiedAt;
}
