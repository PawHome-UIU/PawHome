package com.pawhome.backend.report;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import static com.pawhome.backend.report.ReportDtos.GenerateRequest;
import static com.pawhome.backend.report.ReportDtos.ReportRow;

/**
 * The report library. ADMIN ONLY, and this is the only way in.
 *
 * THE DOWNLOAD DELIBERATELY DOES NOT GO THROUGH /api/v1/files. That route is
 * permitAll -- it has to be, so a guest can see a pet's photo -- and an
 * Adoption Summary carries adopter names, emails and what each of them paid.
 * Serving reports from here means the same filter chain that protects every
 * other admin route protects the bytes, and ReportStorage keeps them under a
 * different root so no URL under the public route can reach them even by
 * accident. See ReportStorage's class comment.
 */
@RestController
@RequestMapping("/api/v1/admin/reports")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminReportController {

    private final ReportService service;

    @GetMapping
    public List<ReportRow> list() {
        return service.list();
    }

    /** What the Generate dialog offers. Labels and blurbs live on the server. */
    @GetMapping("/types")
    public List<Map<String, String>> types() {
        return service.types();
    }

    @PostMapping
    public ReportRow generate(@Valid @RequestBody GenerateRequest request) {
        return service.generate(request);
    }

    /**
     * The file itself.
     *
     * Content-Disposition: attachment, always. A CSV is not something to
     * render in a tab, and the filename is the human-readable one from the
     * row rather than the random name on disk.
     *
     * NO CACHE HEADER, unlike FileController. A pet photo at a random name
     * never changes and is public; a report is private, and a shared or proxy
     * cache holding one is exactly the leak this controller exists to prevent.
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        ReportService.Download file = service.download(id);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + safeName(file.fileName()) + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(file.resource());
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    /**
     * Keep a filename from becoming a header.
     *
     * ReportService builds every name from an enum stem and two LocalDates, so
     * today none of them can contain a quote or a line break and this cannot
     * fire. It is here anyway because the value travels through the database
     * between being built and being interpolated into a response header, and
     * "it is safe because of where it came from" is the assumption that stops
     * being true the first time somebody adds a name the admin can type.
     */
    private static String safeName(String name) {
        return name.replaceAll("[\"\\r\\n\\\\]", "_");
    }
}
