package com.vendorflow.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vendorflow.document.infrastructure.storage.FilesystemObjectStorage;
import com.vendorflow.document.infrastructure.storage.ObjectNotFoundException;
import com.vendorflow.document.infrastructure.storage.StorageKeys;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The filesystem storage, including every traversal trick we could think of. No Spring, no database. */
class FilesystemObjectStorageTest {

    @TempDir Path temp;
    Path root;
    FilesystemObjectStorage storage;

    @BeforeEach
    void setUp() {
        root = temp.resolve("storage");
        storage = new FilesystemObjectStorage(root);
    }

    static InputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    String read(String key) throws IOException {
        try (InputStream in = storage.get(key)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ---- behavior ----

    @Test
    void putGetExistsDeleteRoundTrip() throws IOException {
        String key = StorageKeys.documentKey(UUID.randomUUID(), UUID.randomUUID());
        assertThat(storage.exists(key)).isFalse();
        storage.put(key, bytes("hello"), 5);
        assertThat(storage.exists(key)).isTrue();
        assertThat(read(key)).isEqualTo("hello");
        storage.delete(key);
        assertThat(storage.exists(key)).isFalse();
        storage.delete(key); // deleting an absent object is not an error
    }

    @Test
    void getOfMissingObjectIsNotFound() {
        assertThatThrownBy(() -> storage.get("org/" + UUID.randomUUID() + "/doc/" + UUID.randomUUID()))
                .isInstanceOf(ObjectNotFoundException.class);
    }

    @Test
    void failedWriteLeavesNoObjectAndNoTempFile() throws IOException {
        String key = "org/o1/doc/d1";
        InputStream failing = new InputStream() {
            int n;

            @Override
            public int read() {
                if (n++ < 10) {
                    return 'x';
                }
                throw new IllegalStateException("source failed");
            }
        };
        assertThatThrownBy(() -> storage.put(key, failing, -1)).isInstanceOf(IllegalStateException.class);
        assertThat(storage.exists(key)).isFalse();
        try (Stream<Path> files = Files.walk(root)) {
            assertThat(files.filter(Files::isRegularFile).toList()).as("no temp files left behind").isEmpty();
        }
    }

    @Test
    void successfulWriteLeavesOnlyTheObject() throws IOException {
        storage.put("org/o1/doc/d1", bytes("data"), 4);
        try (Stream<Path> files = Files.walk(root)) {
            assertThat(files.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).toList())
                    .containsExactly("d1");
        }
    }

    // ---- traversal and malformed keys ----

    static List<String> badKeys() {
        return List.of(
                "../outside",
                "../../etc/passwd",
                "org/../../outside",
                "org/o1/../../../outside",
                "/etc/passwd",
                "/org/o1/doc/d1",
                "org/o1/doc/d1/",
                "org//doc/d1",
                "org\\o1\\doc\\d1",
                "..\\..\\windows\\system32",
                "C:\\Windows\\win.ini",
                "C:/Windows/win.ini",
                "%2e%2e/%2e%2e/etc/passwd",
                "..%2f..%2fetc%2fpasswd",
                "%252e%252e/secret",
                "org/o1/doc/d1\0.txt",
                "org/o1/doc/d1\n",
                "org/o1/doc/D1",
                "org/o1/doc/d1.pdf",
                "org/o1/./d1",
                ".",
                "..",
                "",
                " ",
                "org/o1/doc/d 1",
                "file:///etc/passwd",
                "\u202Eorg/o1",
                "org/" + "a".repeat(300));
    }

    @Test
    void everyMaliciousOrMalformedKeyIsRejectedForEveryOperation() throws IOException {
        Path canary = temp.resolve("outside");
        Files.writeString(canary, "secret");
        for (String key : badKeys()) {
            assertThat(StorageKeys.isValid(key)).as("isValid(%s)", key).isFalse();
            assertThatThrownBy(() -> storage.put(key, bytes("x"), 1)).as("put %s", key)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.get(key)).as("get %s", key).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.delete(key)).as("delete %s", key)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.exists(key)).as("exists %s", key)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(Files.readString(canary)).isEqualTo("secret");
    }

    @Test
    void nullKeyIsRejected() {
        assertThatThrownBy(() -> storage.get(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void errorMessageNeverEchoesTheKey() {
        assertThatThrownBy(() -> storage.get("../../secret-name"))
                .hasMessageNotContaining("secret-name");
    }

    @Test
    void validKeysStayInsideTheRoot() throws IOException {
        storage.put("org/aaa/doc/bbb", bytes("x"), 1);
        try (Stream<Path> files = Files.walk(root)) {
            assertThat(files.filter(Files::isRegularFile).allMatch(p -> p.normalize().startsWith(root.toAbsolutePath())))
                    .isTrue();
        }
    }
}
