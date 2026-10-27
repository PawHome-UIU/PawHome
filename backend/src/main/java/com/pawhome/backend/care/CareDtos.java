package com.pawhome.backend.care;

import jakarta.validation.constraints.*;

import java.time.LocalDateTime;
import java.util.List;

public final class CareDtos {

    private CareDtos() {}

    public record CareTaskRow(
            Long id, Long petId, String petName, String petCoverPhotoPath,
            Long boardingId,
            CareTaskType taskType, String description,
            LocalDateTime dueAt, CarePriority priority, CareTaskStatus status,
            Long assignedTo, String assignedToName,
            String completedByName, LocalDateTime completedAt,
            /**
             * DERIVED, NEVER STORED. PENDING with a due_at in the past. V1 is
             * explicit at the column: storing it would need a job whose only
             * purpose is keeping a boolean true, and the boolean would be
             * wrong for as long as that job was late.
             */
            boolean overdue
    ) {}

    public record CareLogRow(
            Long id, Long petId, String petName, Long taskId,
            CareTaskType taskType, String notes,
            String loggedByName, LocalDateTime loggedAt
    ) {}

    /** Somebody a task can be assigned to: staff at the caller's own shelter. */
    public record ColleagueRow(Long id, String fullName) {}

    public record CareCounts(long pending, long overdue, long critical, long doneToday) {}

    public record CareTaskRequest(
            @NotNull Long petId,
            @NotNull(message = "Choose what kind of task this is.") CareTaskType taskType,
            @NotBlank(message = "Say what needs doing.")
            @Size(max = 200) String description,
            @NotNull(message = "When is it due?") LocalDateTime dueAt,
            @NotNull CarePriority priority,
            /** Optional: an unassigned task belongs to the whole shelter. */
            Long assignedTo
    ) {}

    /**
     * Logging care.
     *
     * taskId is optional because a log does not need a task -- "found him
     * limping" answers to nothing that was scheduled. When it IS present the
     * service marks that task done in the same transaction, because a log
     * against a task that stayed pending would make the rota disagree with
     * the record of what was done.
     */
    public record CareLogRequest(
            @NotNull Long petId,
            Long taskId,
            @NotNull CareTaskType taskType,
            @NotBlank(message = "Write what you did.")
            @Size(max = 4000) String notes
    ) {}

    public record CareBoard(CareCounts counts, List<CareTaskRow> tasks, List<CareLogRow> logs) {}
}
