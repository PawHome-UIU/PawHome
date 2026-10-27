package com.pawhome.backend.report;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalDateTime;

public final class ReportDtos {

    private ReportDtos() {}

    /**
     * One row of the report library.
     *
     * shelterName and generatedByName are JOINED IN rather than stored: both
     * foreign keys are ON DELETE SET NULL, so the row can outlive either, and
     * a copied name would go stale the first time a shelter is renamed.
     */
    public record ReportRow(
            Long id,
            String fileName,
            ReportType reportType,
            /** "Adoption Summary" -- the enum's label, so the UI needs no map. */
            String typeLabel,
            LocalDate dateFrom,
            LocalDate dateTo,
            /** NULL = every shelter. */
            Long shelterId,
            String shelterName,
            /** NULL = the account that generated it has been deleted. */
            String generatedByName,
            LocalDateTime generatedAt,
            /** Bytes on disk, for the "412 KB" line. -1 if the file is gone. */
            long sizeBytes
    ) {}

    public record GenerateRequest(
            @NotNull(message = "Choose a report type.") ReportType type,
            @NotNull(message = "Pick the start of the window.") LocalDate from,
            @NotNull(message = "Pick the end of the window.") LocalDate to,
            /** Null = every shelter. */
            Long shelterId
    ) {}
}
