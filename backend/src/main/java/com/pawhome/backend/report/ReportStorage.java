package com.pawhome.backend.report;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Base64;

/**
 * Where report files live -- A DIFFERENT ROOT FROM EVERY OTHER UPLOAD, ON PURPOSE.
 *
 * FileController is permitAll, because a guest browsing pets has no token and
 * still has to see their photos. Its own class comment states the invariant
 * that follows: "nothing that IS a secret may ever be stored under
 * app.uploads.dir." A report is exactly that -- an Adoption Summary carries
 * adopter names, emails, addresses and what they paid.
 *
 * SO THE SAFETY HERE IS STRUCTURAL, NOT A RULE SOMEBODY HAS TO REMEMBER.
 * StorageService.read() resolves against app.uploads.dir and rejects anything
 * that normalises outside it; reports live under app.reports.dir, which is a
 * different tree. There is therefore NO STRING a caller can put in the public
 * files URL that reaches a report -- not a guessed name, not a traversal, not
 * a symlink-shaped path. Compare a deny-list ("refuse paths starting with
 * reports/"), which is one forgotten branch away from leaking the lot.
 *
 * Reports are downloaded only through AdminReportController, behind
 * /api/v1/admin/**, which SecurityConfig gates to ADMIN.
 */
@Component
public class ReportStorage {

    private static final Logger log = LoggerFactory.getLogger(ReportStorage.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path root;

    public ReportStorage(@Value("${app.reports.dir:reports}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    @PostConstruct
    void prepare() throws IOException {
        Files.createDirectories(root);
        log.info("Reports directory: {}", root);
    }

    /**
     * Write one report and return the path to store on the row.
     *
     * The name is 128 random bits plus the date, NOT the report's title. Two
     * "Adoption Summary" reports for September would otherwise collide, and
     * the second would silently overwrite the first -- including the one an
     * older row still points at. The human-readable name lives in
     * reports.file_name and is applied at download time.
     */
    String write(byte[] content, LocalDate on) {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        String name = on + "-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) + ".csv";

        Path target = root.resolve(name);
        try {
            Files.write(target, content);
        } catch (IOException e) {
            log.error("Could not write {}", target, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "The report could not be saved.");
        }
        return name;
    }

    /**
     * Read one back.
     *
     * The normalise-then-check is the same shape as StorageService.read and
     * for the same reason: resolve() joins, normalize() collapses any "..",
     * and only the RESULT is compared against the root. A stored path is
     * written by this class and not by a caller, but the check costs nothing
     * and survives somebody later passing one through from a request.
     */
    Resource read(String storedName) {
        Path file = root.resolve(storedName).normalize();
        if (!file.startsWith(root)) {
            log.warn("Refused a path-traversal read: {}", storedName);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such report.");
        }
        if (!Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "That report's file is missing.");
        }
        try {
            return new ByteArrayResource(Files.readAllBytes(file));
        } catch (IOException e) {
            log.error("Could not read {}", file, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "That report could not be read.");
        }
    }

    /**
     * Size on disk, or -1 when the file is not there.
     *
     * -1 RATHER THAN 0, because the two are different facts and the library
     * should not present a vanished file as an empty one. A row whose bytes
     * have been removed from under it -- a restored backup, a cleared volume --
     * is worth showing as "missing" so somebody regenerates it, instead of
     * silently offering a download that will 404.
     */
    long sizeOf(String storedName) {
        if (storedName == null || storedName.isBlank()) return -1;
        Path file = root.resolve(storedName).normalize();
        if (!file.startsWith(root)) return -1;
        try {
            return Files.isRegularFile(file) ? Files.size(file) : -1;
        } catch (IOException e) {
            return -1;
        }
    }

    /** Best effort. A missing file is the desired end state either way. */
    void delete(String storedName) {
        if (storedName == null || storedName.isBlank()) return;
        Path file = root.resolve(storedName).normalize();
        if (!file.startsWith(root)) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete {}", file, e);
        }
    }
}
