package com.vendorflow.document.infrastructure.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local-disk storage for development and tests (and a single-host deployment). Defense in depth against path
 * traversal: (1) the key must match the strict {@link StorageKeys#isValid} format (so no "..", no absolute or
 * backslash paths, no encoded characters), (2) the resolved + normalized path must still start with the root.
 * Writes go to a temp file in the target directory and are moved into place atomically.
 */
public class FilesystemObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(FilesystemObjectStorage.class);

    private final Path root;

    public FilesystemObjectStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new StorageException("Cannot create storage root", e);
        }
    }

    @Override
    public void put(String key, InputStream content, long sizeHint) {
        Path target = resolve(key);
        Path tmp = null;
        try {
            Files.createDirectories(target.getParent());
            // Temp names start with '.' and keys can never contain '.', so a temp file cannot collide with an object.
            tmp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            Files.copy(content, tmp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target);
            }
            tmp = null;
        } catch (IOException e) {
            throw new StorageException("Cannot store object", e);
        } finally {
            // Also runs when reading the source stream threw a RuntimeException (e.g. size limit exceeded).
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException e) {
                    log.warn("Could not remove temporary upload file {}", tmp.getFileName());
                }
            }
        }
    }

    @Override
    public InputStream get(String key) {
        Path path = resolve(key);
        try {
            return Files.newInputStream(path);
        } catch (NoSuchFileException e) {
            throw new ObjectNotFoundException("Object not found");
        } catch (IOException e) {
            throw new StorageException("Cannot read object", e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new StorageException("Cannot delete object", e);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    /** Package-private for tests: the validated absolute path of a key. */
    Path resolve(String key) {
        if (!StorageKeys.isValid(key)) {
            // The key is not echoed: it could be attacker-influenced and end up in logs.
            throw new IllegalArgumentException("Invalid storage key");
        }
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root) || path.equals(root)) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return path;
    }
}
