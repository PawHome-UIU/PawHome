package com.pawhome.backend.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Serving uploaded images back.
 *
 * PUBLIC, and it has to be: a pet's photo appears on the guest browse page,
 * which has no token. The names are 128 random bits, so a path is not
 * guessable, but that is obscurity and not access control -- nothing behind
 * this route is a secret, and nothing that IS a secret may ever be stored
 * under app.uploads.dir.
 *
 * Two headers earn their place. Content-Disposition: inline with a fixed
 * filename stops a browser from taking a name from the URL. X-Content-Type-
 * Options: nosniff stops it from second-guessing the type we declare, which is
 * the other half of only storing extensions this service chose.
 */
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FileController {

    private final StorageService storage;

    @GetMapping("/{folder}/{name}")
    public ResponseEntity<Resource> read(@PathVariable String folder,
                                         @PathVariable String name) {
        String stored = folder + "/" + name;
        Resource file = storage.read(stored);

        return ResponseEntity.ok()
                .contentType(storage.contentType(stored))
                // Images render; PDFs download. See StorageService.isInlineSafe:
                // a PDF can carry script and this origin is open to anyone.
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        storage.isInlineSafe(stored)
                                ? "inline; filename=\"image\""
                                : "attachment; filename=\"" + name + "\"")
                .header("X-Content-Type-Options", "nosniff")
                // The bytes at a given name never change -- a replacement gets
                // a new random name -- so this can be cached hard.
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                .body(file);
    }
}
