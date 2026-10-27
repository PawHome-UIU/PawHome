package com.pawhome.backend.report;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One generated file. Maps `reports` from V1__init.sql.
 *
 * THIS TABLE HAS EXISTED SINCE V1 AND NOTHING HAD EVER WRITTEN A ROW TO IT.
 * The Reports screen listed four hard-coded PDFs with names like
 * "Quarterly_Impact_Q3.pdf" and a Download button that did nothing.
 *
 * WHAT THE ROW IS FOR. The file on disk is the output; this row is the RECORD
 * that it was produced -- by whom, over what window, for which shelter. That
 * is the difference between a report library and a download: another admin can
 * find last quarter's file next week without regenerating it, and can see it
 * covered exactly the dates it claims to.
 *
 * BOTH OWNER COLUMNS ARE NULLABLE AND BOTH MEAN SOMETHING:
 *
 *   shelter_id   NULL = every shelter, per V1's own comment.
 *   generated_by NULL = the account that generated it has since been deleted
 *                       (fk_reports_author is ON DELETE SET NULL), so the file
 *                       outlives its author rather than vanishing with them.
 */
@Entity
@Table(name = "reports")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** What the admin sees when they download: "Adoption_Summary_2026-09.csv". */
    @Column(name = "file_name", nullable = false, length = 160)
    private String fileName;

    /**
     * Where the bytes live, relative to app.reports.dir.
     *
     * NOT under app.uploads.dir, and that is a security boundary rather than
     * tidiness -- /api/v1/files/** is permitAll so that a guest can see a pet's
     * photo, and these files carry adopter names, emails and addresses. See
     * ReportStorage.
     */
    @Column(name = "file_path", nullable = false, length = 255)
    private String filePath;

    @Enumerated(EnumType.STRING)
    @Column(name = "report_type", nullable = false)
    private ReportType reportType;

    @Column(name = "date_from", nullable = false)
    private LocalDate dateFrom;

    @Column(name = "date_to", nullable = false)
    private LocalDate dateTo;

    /** NULL = all shelters. */
    @Column(name = "shelter_id")
    private Long shelterId;

    /** NULL = the author's account was deleted. */
    @Column(name = "generated_by")
    private Long generatedBy;

    // Written by the column default. insertable=false is what makes that true:
    // without it Hibernate sends an explicit NULL and the default never fires.
    @Column(name = "generated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime generatedAt;
}
