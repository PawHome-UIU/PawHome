package com.pawhome.backend.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the upload path refuses.
 *
 * A REAL TEST, NOT A MOCKED ONE. Every assertion here is about bytes and paths
 * on a real filesystem, because the things that go wrong in file handling --
 * a "../" that escapes the root, an extension the browser will execute -- are
 * exactly the things a mock would be happy to pretend did not happen.
 */
class StorageServiceTest {

    @TempDir Path tmp;
    private StorageService storage;

    @BeforeEach
    void setUp() throws IOException {
        storage = new StorageService(tmp.toString());
        storage.ready();
    }

    private static MockMultipartFile image(String name, String type) {
        return new MockMultipartFile("file", name, type, new byte[]{1, 2, 3, 4});
    }

    @Test
    @DisplayName("the client's filename is thrown away entirely")
    void filenameIsIgnored() {
        // "../../application-secret.yaml" is the obvious attack. The point of
        // this test is that the name is not sanitised, it is unused: the stored
        // name shares nothing with what was sent.
        String stored = storage.store(
                image("../../application-secret.yaml", MediaType.IMAGE_JPEG_VALUE), "pets");

        assertThat(stored).startsWith("pets/").endsWith(".jpg");
        assertThat(stored).doesNotContain("..").doesNotContain("secret");
        assertThat(tmp.resolve(stored)).exists();
    }

    @Test
    @DisplayName("the extension comes from the content type, never the name")
    void extensionFromContentType() {
        // A browser decides how to treat a response partly by its extension, so
        // "photo.jpg.html" stored under its own name would let an uploader put
        // a page on this origin -- same-origin with the token in localStorage.
        String stored = storage.store(image("photo.jpg.html", MediaType.IMAGE_PNG_VALUE), "pets");
        assertThat(stored).endsWith(".png");
    }

    @Test
    @DisplayName("two uploads of the same name do not collide")
    void namesAreUnique() {
        String a = storage.store(image("cat.jpg", MediaType.IMAGE_JPEG_VALUE), "pets");
        String b = storage.store(image("cat.jpg", MediaType.IMAGE_JPEG_VALUE), "pets");
        assertThat(a).isNotEqualTo(b);
        assertThat(tmp.resolve(a)).exists();
        assertThat(tmp.resolve(b)).exists();
    }

    @Test
    @DisplayName("SVG is refused, because it carries script")
    void svgRefused() {
        // The reason SVG is missing from the allowlist and not merely
        // "not thought about": it is an image that can execute.
        assertThatThrownBy(() -> storage.store(image("x.svg", "image/svg+xml"), "pets"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("415");
    }

    @Test
    @DisplayName("a non-image type is refused")
    void otherTypesRefused() {
        assertThatThrownBy(() -> storage.store(image("x.html", MediaType.TEXT_HTML_VALUE), "pets"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> storage.store(image("x.pdf", MediaType.APPLICATION_PDF_VALUE), "pets"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("TRAVERSAL: a read cannot escape the uploads root")
    void readCannotEscape() throws IOException {
        // A real file to reach for, outside the root, so a pass here means the
        // check worked rather than the target happening not to exist.
        Path secret = tmp.getParent().resolve("application-secret.yaml");
        Files.writeString(secret, "gmail-app-password: hunter2");

        for (String attempt : new String[]{
                "../application-secret.yaml",
                "pets/../../application-secret.yaml",
                "pets/./../../application-secret.yaml"}) {
            assertThatThrownBy(() -> storage.read(attempt))
                    .as("read(%s)", attempt)
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("404");
        }
    }

    @Test
    @DisplayName("a file inside the root, with a servable extension, reads back")
    void readsWhatItWrote() {
        String stored = storage.store(image("cat.jpg", MediaType.IMAGE_JPEG_VALUE), "pets");
        assertThat(storage.read(stored).exists()).isTrue();
        assertThat(storage.contentType(stored)).isEqualTo(MediaType.IMAGE_JPEG);
    }

    @Test
    @DisplayName("a bucket name is a literal word, not something a caller computes")
    void bucketMustBeLiteral() {
        // Not a user-facing refusal: it fires only if a caller starts passing
        // something dynamic, which is how a bucket becomes a path.
        assertThatThrownBy(() -> storage.store(
                image("cat.jpg", MediaType.IMAGE_JPEG_VALUE), "../etc"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("delete never escapes the root either")
    void deleteCannotEscape() throws IOException {
        Path secret = tmp.getParent().resolve("keep-me.txt");
        Files.writeString(secret, "still here");

        storage.delete("../keep-me.txt");

        assertThat(secret).exists();
    }
}
