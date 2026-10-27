package com.pawhome.backend.report;

import com.pawhome.backend.security.ScopeGuard;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.pawhome.backend.report.ReportDtos.GenerateRequest;
import static com.pawhome.backend.report.ReportDtos.ReportRow;

/**
 * Generating, listing and serving report files.
 *
 * WHAT THIS REPLACES. `reports` was created in V1 and no code had ever
 * inserted a row. The Reports screen listed four invented PDFs and its
 * Download button did nothing at all, so the whole feature was a picture of
 * itself.
 *
 * ONE FILE FORMAT: CSV. Not because a PDF would be worse to read, but because
 * a PDF means a new dependency, and every one of these reports is a table that
 * somebody is going to want to sort, filter or total. A CSV opens in Excel and
 * in Sheets and in a text editor, and it can be diffed. QUARTERLY_IMPACT is
 * the exception -- it is a summary rather than a table -- and it is written as
 * metric/value pairs, which is the honest shape of a summary in this format.
 *
 * THE FILE IS WRITTEN BEFORE THE ROW IS SAVED, and that order is deliberate:
 * a row pointing at a file that does not exist is a broken download somebody
 * has to investigate, whereas a file with no row is invisible bytes that cost
 * nothing. It is the same order the pet photos and the shelter logo use.
 *
 * EVERY QUERY IS PARAMETERISED, INCLUDING THE SHELTER FILTER. There is no
 * string concatenation anywhere below, so nothing that arrives in the request
 * can reach the SQL as syntax -- and the CSV those queries feed has its own
 * defence in Csv.field(), because a value can be dangerous to the SPREADSHEET
 * long after it was harmless to the database.
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    /**
     * The UTF-8 byte-order mark.
     *
     * Excel on Windows reads a CSV in the system codepage unless the file
     * opens with this, so "Peña" arrives as "PeÃ±a" -- on a report full of
     * people's names. Three bytes to avoid it.
     */
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /** A window longer than this is a mistake, not a request. */
    private static final int MAX_WINDOW_DAYS = 366 * 5;

    private final ReportRepository repository;
    private final ReportStorage storage;
    private final JdbcTemplate jdbc;
    private final ScopeGuard scopeGuard;

    // ------------------------------------------------------------- reading

    @Transactional(readOnly = true)
    public List<ReportRow> list() {
        return jdbc.query("""
                SELECT r.id, r.file_name, r.file_path, r.report_type,
                       r.date_from, r.date_to, r.shelter_id,
                       sh.name AS shelter_name, u.full_name AS generated_by_name,
                       r.generated_at
                  FROM reports r
                  LEFT JOIN shelters sh ON sh.id = r.shelter_id
                  LEFT JOIN users u     ON u.id  = r.generated_by
                 ORDER BY r.generated_at DESC, r.id DESC
                """,
                (rs, i) -> {
                    ReportType type = ReportType.valueOf(rs.getString("report_type"));
                    return new ReportRow(
                            rs.getLong("id"),
                            rs.getString("file_name"),
                            type,
                            type.label(),
                            rs.getObject("date_from", LocalDate.class),
                            rs.getObject("date_to", LocalDate.class),
                            rs.getObject("shelter_id") == null ? null : rs.getLong("shelter_id"),
                            rs.getString("shelter_name"),
                            rs.getString("generated_by_name"),
                            rs.getObject("generated_at", java.time.LocalDateTime.class),
                            storage.sizeOf(rs.getString("file_path")));
                });
    }

    /** The bytes, plus the name to hand the browser. */
    @Transactional(readOnly = true)
    public Download download(Long id) {
        Report report = load(id);
        return new Download(storage.read(report.getFilePath()), report.getFileName());
    }

    public record Download(Resource resource, String fileName) {}

    // ------------------------------------------------------------ writing

    @Transactional
    public ReportRow generate(GenerateRequest r) {
        if (r.to().isBefore(r.from())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The end date is before the start date.");
        }
        if (r.from().plusDays(MAX_WINDOW_DAYS).isBefore(r.to())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That window is longer than five years.");
        }
        String shelterName = null;
        if (r.shelterId() != null) {
            List<String> names = jdbc.queryForList(
                    "SELECT name FROM shelters WHERE id = ?", String.class, r.shelterId());
            if (names.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such shelter.");
            }
            shelterName = names.get(0);
        }

        String csv = switch (r.type()) {
            case ADOPTION_SUMMARY -> adoptionSummary(r);
            case SHELTER_EFFICIENCY -> shelterEfficiency(r);
            case PET_INVENTORY -> petInventory(r);
            case QUARTERLY_IMPACT -> quarterlyImpact(r, shelterName);
        };

        byte[] bytes = withBom(csv);
        String storedPath = storage.write(bytes, LocalDate.now());

        Long me = scopeGuard.require().id();
        Report saved = repository.save(Report.builder()
                .fileName(fileNameFor(r))
                .filePath(storedPath)
                .reportType(r.type())
                .dateFrom(r.from())
                .dateTo(r.to())
                .shelterId(r.shelterId())
                .generatedBy(me)
                .build());

        log.info("Admin {} generated report {} ({}, {} to {})",
                me, saved.getId(), r.type(), r.from(), r.to());

        // Read back rather than mapping the entity: generated_at is written by
        // the column default and is null on the object just saved.
        return list().stream()
                .filter(row -> row.id().equals(saved.getId()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "The report was not saved."));
    }

    /**
     * Remove a report and its file.
     *
     * ROW FIRST, THEN THE FILE -- if the delete is going to be refused it is
     * refused while the bytes are still there. Nothing references a report, so
     * unlike a pet or a shelter this genuinely is a delete rather than a
     * status change: the file was a derived artefact and can be regenerated
     * from the same window.
     */
    @Transactional
    public void delete(Long id) {
        Report report = load(id);
        String path = report.getFilePath();
        repository.delete(report);
        repository.flush();
        storage.delete(path);
        log.warn("Admin {} deleted report {}", scopeGuard.require().id(), id);
    }

    // ------------------------------------------------------- the four builders

    /**
     * One row per animal placed in the window.
     *
     * The adopter's email is included because the point of this report is
     * reconciliation -- matching a placement against a person -- and that is
     * exactly why the file must not be downloadable without a token. See
     * ReportStorage.
     */
    private String adoptionSummary(GenerateRequest r) {
        List<String> rows = jdbc.query("""
                SELECT a.adopted_on, p.name AS pet, p.species, p.breed,
                       sh.name AS shelter, u.full_name AS adopter, u.email,
                       a.fee_paid,
                       DATEDIFF(a.adopted_on, DATE(app.submitted_at)) AS days
                  FROM adoptions a
                  JOIN pets p                    ON p.id   = a.pet_id
                  JOIN shelters sh               ON sh.id  = a.shelter_id
                  JOIN users u                   ON u.id   = a.adopter_id
                  JOIN adoption_applications app ON app.id = a.application_id
                 WHERE a.adopted_on BETWEEN ? AND ?
                   AND (? IS NULL OR a.shelter_id = ?)
                 ORDER BY a.adopted_on, p.name
                """,
                (rs, i) -> {
                    long days = rs.getLong("days");
                    return Csv.row(
                            rs.getObject("adopted_on", LocalDate.class),
                            rs.getString("pet"), rs.getString("species"), rs.getString("breed"),
                            rs.getString("shelter"), rs.getString("adopter"), rs.getString("email"),
                            rs.getBigDecimal("fee_paid"),
                            // Negative means the adoption is dated before its
                            // own application -- nothing forbids that, since a
                            // CHECK cannot span two tables. An empty cell, not
                            // a nonsense number that would poison a column
                            // somebody averages.
                            days < 0 ? null : days);
                },
                r.from(), r.to(), r.shelterId(), r.shelterId());

        return Csv.row("Adopted on", "Pet", "Species", "Breed", "Shelter",
                       "Adopter", "Adopter email", "Fee paid", "Days to place")
             + String.join("", rows);
    }

    /**
     * One row per shelter, whether or not it did anything.
     *
     * THE APPROVAL RATE'S DENOMINATOR IS DECIDED APPLICATIONS, matching the
     * Analytics tile exactly -- two places computing the same figure two ways
     * is how one of them starts lying. Pending ones are not failures, and a
     * withdrawal is the applicant's decision rather than the shelter's.
     *
     * A shelter with no applications gets empty cells for the rate and the
     * average, not zeros: "0% approved" and "decided nothing" are different
     * facts, and a zero here would drag any average taken over the column.
     */
    private String shelterEfficiency(GenerateRequest r) {
        List<String> rows = jdbc.query("""
                SELECT sh.name AS shelter, sh.city, sh.capacity,
                       (SELECT COUNT(*) FROM pets p2
                         WHERE p2.shelter_id = sh.id)                     AS pets_held,
                       COUNT(app.id)                                      AS applications,
                       SUM(app.status = 'APPROVED')                       AS approved,
                       SUM(app.status = 'REJECTED')                       AS rejected,
                       SUM(app.status = 'PENDING')                        AS pending,
                       SUM(app.status = 'WITHDRAWN')                      AS withdrawn,
                       ROUND(SUM(app.status = 'APPROVED') * 100
                             / NULLIF(SUM(app.status IN ('APPROVED','REJECTED')), 0)
                            )                                             AS approval_pct,
                       ROUND(AVG(CASE WHEN app.reviewed_at IS NOT NULL
                                 THEN TIMESTAMPDIFF(HOUR, app.submitted_at, app.reviewed_at) / 24
                                 END), 1)                                 AS avg_days,
                       (SELECT COUNT(*) FROM adoptions a
                         WHERE a.shelter_id = sh.id
                           AND a.adopted_on BETWEEN ? AND ?)              AS adoptions
                  FROM shelters sh
                  -- The applications reach the shelter through its pets, which
                  -- is the only route: adoption_applications has no shelter_id
                  -- of its own, and copying one there would let the two
                  -- disagree the day a pet is transferred.
                  LEFT JOIN pets p ON p.shelter_id = sh.id
                  LEFT JOIN adoption_applications app
                         ON app.pet_id = p.id
                        AND app.submitted_at >= ?
                        AND app.submitted_at <  DATE_ADD(?, INTERVAL 1 DAY)
                 WHERE (? IS NULL OR sh.id = ?)
                 GROUP BY sh.id, sh.name, sh.city, sh.capacity
                 ORDER BY sh.name
                """,
                (rs, i) -> Csv.row(
                        rs.getString("shelter"), rs.getString("city"), rs.getInt("capacity"),
                        rs.getLong("pets_held"), rs.getLong("applications"),
                        rs.getLong("approved"), rs.getLong("rejected"),
                        rs.getLong("pending"), rs.getLong("withdrawn"),
                        // getObject, not getInt: both are NULL when the shelter
                        // decided nothing, and getInt would turn that into a
                        // confident 0.
                        rs.getObject("approval_pct"), rs.getObject("avg_days"),
                        rs.getLong("adoptions")),
                // BETWEEN for the adoptions subquery, then the half-open
                // window for applications: submitted_at is a DATETIME, so
                // "<= @to" would drop everything submitted after midnight on
                // the last day. DATE_ADD(?, INTERVAL 1 DAY) with a strict "<"
                // includes the whole final day.
                r.from(), r.to(), r.from(), r.to(), r.shelterId(), r.shelterId());

        return Csv.row("Shelter", "City", "Capacity", "Pets held", "Applications",
                       "Approved", "Rejected", "Pending", "Withdrawn",
                       "Approval rate %", "Avg days to decide", "Adoptions")
             + String.join("", rows);
    }

    /**
     * Who is in the shelters right now.
     *
     * A SNAPSHOT, SO THE DATE WINDOW DOES NOT FILTER IT -- there is no sense
     * in which a pet currently on the catalogue was "in" a past month. The
     * window is still stored on the row, because that is what makes an old
     * file interpretable later.
     *
     * `Vaccinated` IS THE SAME EXPRESSION v_pet_listing USES: at least one
     * COMPLETED shot and nothing SCHEDULED past its due date. Written a second
     * way it would eventually disagree with the tick on the pet's profile.
     */
    private String petInventory(GenerateRequest r) {
        List<String> rows = jdbc.query("""
                SELECT sh.name AS shelter, p.name AS pet, p.species, p.breed,
                       p.sex, p.size, p.age_value, p.age_unit, p.status,
                       p.adoption_fee,
                       (    EXISTS (SELECT 1 FROM vaccinations v
                                     WHERE v.pet_id = p.id AND v.status = 'COMPLETED')
                        AND NOT EXISTS (SELECT 1 FROM vaccinations v2
                                         WHERE v2.pet_id = p.id
                                           AND v2.status = 'SCHEDULED'
                                           AND v2.next_due_on < CURDATE())
                       )                                            AS vaccinated,
                       (SELECT COUNT(*) FROM adoption_applications a2
                         WHERE a2.pet_id = p.id AND a2.status = 'PENDING') AS open_apps
                  FROM pets p
                  JOIN shelters sh ON sh.id = p.shelter_id
                 WHERE p.shelter_id IS NOT NULL
                   AND (? IS NULL OR p.shelter_id = ?)
                 ORDER BY sh.name, p.species, p.name
                """,
                (rs, i) -> Csv.row(
                        rs.getString("shelter"), rs.getString("pet"), rs.getString("species"),
                        rs.getString("breed"), rs.getString("sex"), rs.getString("size"),
                        rs.getInt("age_value") + " " + rs.getString("age_unit").toLowerCase(),
                        rs.getString("status"), rs.getBigDecimal("adoption_fee"),
                        rs.getBoolean("vaccinated") ? "Yes" : "No",
                        rs.getLong("open_apps")),
                r.shelterId(), r.shelterId());

        return Csv.row("Shelter", "Pet", "Species", "Breed", "Sex", "Size",
                       "Age", "Status", "Adoption fee", "Vaccinated", "Open applications")
             + String.join("", rows);
    }

    /**
     * The headline numbers as metric/value pairs.
     *
     * NOT A TABLE, so it is not written as one. A summary forced into columns
     * gives a one-row file with twelve headers that nobody can read; two
     * columns down the page is how a person actually reads a set of figures.
     */
    private String quarterlyImpact(GenerateRequest r, String shelterName) {
        StringBuilder sb = new StringBuilder();
        sb.append(Csv.row("Metric", "Value"));
        sb.append(Csv.row("Report window", r.from() + " to " + r.to()));
        sb.append(Csv.row("Shelter", shelterName == null ? "All shelters" : shelterName));
        sb.append(Csv.row("", ""));

        Long id = r.shelterId();
        long adoptions = count("""
                SELECT COUNT(*) FROM adoptions a
                 WHERE a.adopted_on BETWEEN ? AND ? AND (? IS NULL OR a.shelter_id = ?)
                """, r.from(), r.to(), id, id);
        long applications = count("""
                SELECT COUNT(*) FROM adoption_applications app JOIN pets p ON p.id = app.pet_id
                 WHERE app.submitted_at >= ? AND app.submitted_at < DATE_ADD(?, INTERVAL 1 DAY)
                   AND (? IS NULL OR p.shelter_id = ?)
                """, r.from(), r.to(), id, id);
        long approved = count("""
                SELECT COUNT(*) FROM adoption_applications app JOIN pets p ON p.id = app.pet_id
                 WHERE app.status = 'APPROVED'
                   AND app.submitted_at >= ? AND app.submitted_at < DATE_ADD(?, INTERVAL 1 DAY)
                   AND (? IS NULL OR p.shelter_id = ?)
                """, r.from(), r.to(), id, id);
        long decided = count("""
                SELECT COUNT(*) FROM adoption_applications app JOIN pets p ON p.id = app.pet_id
                 WHERE app.status IN ('APPROVED','REJECTED')
                   AND app.submitted_at >= ? AND app.submitted_at < DATE_ADD(?, INTERVAL 1 DAY)
                   AND (? IS NULL OR p.shelter_id = ?)
                """, r.from(), r.to(), id, id);
        long onCatalogue = count("""
                SELECT COUNT(*) FROM pets p
                 WHERE p.shelter_id IS NOT NULL AND p.status IN ('AVAILABLE','PENDING')
                   AND (? IS NULL OR p.shelter_id = ?)
                """, id, id);
        long boarded = count("""
                SELECT COUNT(*) FROM boarding_requests b
                 WHERE b.status = 'COMPLETED' AND b.end_date BETWEEN ? AND ?
                   AND (? IS NULL OR b.shelter_id = ?)
                """, r.from(), r.to(), id, id);
        Double fees = jdbc.queryForObject("""
                SELECT SUM(a.fee_paid) FROM adoptions a
                 WHERE a.adopted_on BETWEEN ? AND ? AND (? IS NULL OR a.shelter_id = ?)
                """, Double.class, r.from(), r.to(), id, id);

        sb.append(Csv.row("Adoptions completed", adoptions));
        sb.append(Csv.row("Applications received", applications));
        sb.append(Csv.row("Applications decided", decided));
        // Blank rather than 0% when nothing was decided -- see shelterEfficiency.
        sb.append(Csv.row("Approval rate %", decided == 0 ? null : Math.round(approved * 100f / decided)));
        sb.append(Csv.row("Adoption fees collected", fees == null ? "0.00" : String.format("%.2f", fees)));
        sb.append(Csv.row("Boarding stays completed", boarded));
        sb.append(Csv.row("Pets on the catalogue today", onCatalogue));
        return sb.toString();
    }

    // ------------------------------------------------------------ internals

    private Report load(Long id) {
        return repository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such report."));
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0L : n;
    }

    private static byte[] withBom(String csv) {
        byte[] body = csv.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[BOM.length + body.length];
        System.arraycopy(BOM, 0, out, 0, BOM.length);
        System.arraycopy(body, 0, out, BOM.length, body.length);
        return out;
    }

    /**
     * "Adoption_Summary_2026-03-01_2026-09-01.csv".
     *
     * The window is IN THE NAME because these files get downloaded into a
     * folder and looked at months later, and "Adoption_Summary.csv" three
     * times over tells the reader nothing about which is which.
     */
    private static String fileNameFor(GenerateRequest r) {
        return r.type().fileStem() + "_" + r.from() + "_" + r.to() + ".csv";
    }

    /** Report types, for the Generate dialog. Label and blurb come from here. */
    public List<Map<String, String>> types() {
        return List.of(
                Map.of("value", ReportType.ADOPTION_SUMMARY.name(),
                       "label", ReportType.ADOPTION_SUMMARY.label(),
                       "blurb", "Every adoption completed in the window, with the fee "
                              + "and how long the animal waited."),
                Map.of("value", ReportType.SHELTER_EFFICIENCY.name(),
                       "label", ReportType.SHELTER_EFFICIENCY.label(),
                       "blurb", "Applications, approval rate and average review time "
                              + "for each shelter."),
                Map.of("value", ReportType.PET_INVENTORY.name(),
                       "label", ReportType.PET_INVENTORY.label(),
                       "blurb", "Who is in the shelters right now, with vaccination "
                              + "standing. A snapshot -- the dates are not applied."),
                Map.of("value", ReportType.QUARTERLY_IMPACT.name(),
                       "label", ReportType.QUARTERLY_IMPACT.label(),
                       "blurb", "The headline numbers as a short summary, for a board "
                              + "or a funder."));
    }
}
