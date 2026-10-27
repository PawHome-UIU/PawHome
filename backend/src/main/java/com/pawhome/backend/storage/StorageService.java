package com.pawhome.backend.storage;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/**
 * Files on disk, under app.uploads.dir.
 *
 * WHY THE CLIENT'S FILENAME IS THROWN AWAY. It is attacker-controlled text that
 * this code is about to turn into a path. "../../application-secret.yaml" is
 * the obvious attack and the easy one to remember; the ones that get through a
 * hand-rolled sanitiser are the boring cases -- a NUL byte, a Windows
 * alternate data stream, a name that is 300 characters long, two users
 * uploading "cat.jpg" a second apart. None of them exist if the name is not
 * used at all. Every stored file is named from 128 random bits plus an
 * extension this class chose, and the original name is not recorded anywhere.
 *
 * WHY THE EXTENSION COMES FROM THE CONTENT TYPE AND NOT THE NAME. The stored
 * name is what the browser later fetches, and a browser decides how to treat a
 * response partly by its extension. Trusting "photo.jpg.html" would let an
 * uploader put a page of their own on this origin -- same-origin with the app,
 * with access to the token in localStorage. The type is checked against a
 * short allowlist and the extension is derived from it.
 *
 * WHAT THIS IS NOT. Local disk is not durable storage: files live beside the
 * process, so a second instance cannot read what the first one wrote and a
 * container restart on an ephemeral filesystem loses them. It is the right
 * amount of machinery for a project that runs on one machine, and the seam is
 * this class -- swapping it for S3 changes nothing outside this file.
 */
@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * The only types accepted, and the extension each one is stored under.
     *
     * An allowlist rather than a blocklist of dangerous types: a blocklist is a
     * claim to have thought of everything, and text/html is not the only way to
     * get script onto an origin -- image/svg+xml carries it too, which is
     * exactly why SVG is missing from this map.
     */
    private static final Map<String, String> ALLOWED = Map.of(
            MediaType.IMAGE_JPEG_VALUE, ".jpg",
            MediaType.IMAGE_PNG_VALUE, ".png",
            "image/webp", ".webp",
            // PDF is here for one reason: a vaccination certificate is a
            // document, and a vet hands over a PDF far more often than a
            // photograph of one. It is served as an ATTACHMENT rather than
            // inline -- see isInlineSafe -- because a PDF can carry
            // JavaScript, and /api/v1/files is a permitted origin.
            MediaType.APPLICATION_PDF_VALUE, ".pdf");

    /** Extensions this service will serve back. Anything else is a 404. */
    private static final Set<String> SERVABLE = Set.of(".jpg", ".png", ".webp", ".pdf");

    private final Path root;

    public StorageService(@Value("${app.uploads.dir:uploads}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    @PostConstruct
    void ready() throws IOException {
        Files.createDirectories(root);
        log.info("Uploads directory: {}", root);
    }

    /**
     * Store one image and return the name to keep in the database.
     *
     * `folder` is a caller-chosen bucket ("pets", "shelters") and is NOT
     * user input -- see the check below, which exists so it stays that way.
     */
    public String store(MultipartFile file, String folder) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No file was sent.");
        }
        if (!folder.matches("[a-z]+")) {
            // Not a user-facing message: reaching this means a caller passed
            // something dynamic, which is how a bucket name becomes a path.
            throw new IllegalArgumentException("Bucket names are literal lowercase words.");
        }

        String extension = ALLOWED.get(file.getContentType());
        if (extension == null) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Photos must be JPEG, PNG or WebP.");
        }

        // Spring's max-file-size rejects the oversized upload before this
        // method is reached; this is the belt to that braces, and it is the
        // check that survives somebody raising the limit for a different form.
        if (file.getSize() > 10L * 1024 * 1024) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "That image is larger than 10MB.");
        }

        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        String name = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) + extension;

        Path target = root.resolve(folder).resolve(name);
        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("Could not write {}", target, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "That image could not be saved.");
        }

        return folder + "/" + name;
    }

    /**
     * Read a stored file back.
     *
     * THE NORMALISE-THEN-CHECK IS THE WHOLE SECURITY OF THIS METHOD, and the
     * order matters: resolve() joins the path, normalize() collapses any ".."
     * in it, and only then is the result compared against the root. Checking
     * the raw string for ".." first and skipping this would still be beaten by
     * an encoded or unusual spelling; comparing the resolved, absolute path
     * cannot be, because there is no spelling of a path outside the root that
     * normalises to one inside it.
     */
    public Resource read(String storedPath) {
        Path file = root.resolve(storedPath).normalize();

        if (!file.startsWith(root)) {
            log.warn("Refused a path-traversal read: {}", storedPath);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such file.");
        }

        String name = file.getFileName().toString().toLowerCase();
        if (SERVABLE.stream().noneMatch(name::endsWith)) {
            // Nothing else this service wrote can have another extension, so
            // reaching here means a hand-typed URL.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such file.");
        }
        if (!Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such file.");
        }

        return new FileSystemResource(file);
    }

    /** The content type to serve a stored file with, from its extension. */
    public MediaType contentType(String storedPath) {
        String name = storedPath.toLowerCase();
        if (name.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (name.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
        if (name.endsWith(".pdf")) return MediaType.APPLICATION_PDF;
        return MediaType.IMAGE_JPEG;
    }

    /**
     * Whether the browser may RENDER this file rather than download it.
     *
     * Images may: that is the whole point of a pet photo, and a decoder cannot
     * be talked into running script. A PDF may not. Acrobat's format includes
     * JavaScript, /api/v1/files is permitted to anonymous callers by name, and
     * an inline PDF executes on this API's origin -- so certificates come back
     * as a download, which every browser will still open for the person who
     * asked for it.
     */
    public boolean isInlineSafe(String storedPath) {
        return !storedPath.toLowerCase().endsWith(".pdf");
    }

    /**
     * Delete a stored file, quietly.
     *
     * Best-effort on purpose: a replaced pet photo whose file failed to unlink
     * is disk waste, and refusing the replacement over it would be worse. The
     * row is the record; the file is a cache of bytes.
     */
    public void delete(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) return;
        Path file = root.resolve(storedPath).normalize();
        if (!file.startsWith(root)) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete {}: {}", file, e.toString());
        }
    }
}
