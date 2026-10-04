package com.vendorflow.document.infrastructure.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Private S3-compatible storage (Supabase Storage in production, ADR-0010). Same contract as
 * {@link FilesystemObjectStorage}: server-generated keys only (rejected unless {@link StorageKeys#isValid}), atomic
 * writes (S3 PUT is atomic: readers see the old or the new object, never a partial one), nothing public.
 *
 * <p>The upload is spooled to a temporary file first. S3 PUT needs the exact Content-Length up front and the
 * {@code sizeHint} is not trustworthy; the stream is already capped (15 MB) by the caller, so the temp file is
 * bounded, and the object is only sent if the whole stream was read successfully ("if reading fails, nothing is left
 * behind"). The temp file is always removed.
 */
public class S3ObjectStorage implements ObjectStorage, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStorage.class);

    private final S3Client s3;
    private final String bucket;

    public S3ObjectStorage(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public void put(String key, InputStream content, long sizeHint) {
        requireValid(key);
        Path tmp = null;
        try {
            tmp = Files.createTempFile("vf-upload-", ".tmp");
            Files.copy(content, tmp, StandardCopyOption.REPLACE_EXISTING);
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key)
                    // Defense in depth: even if the bucket were ever exposed, the bytes are opaque.
                    .contentType("application/octet-stream").build(), RequestBody.fromFile(tmp));
        } catch (IOException | software.amazon.awssdk.core.exception.SdkException e) {
            // Only the class is reported: SDK messages can contain endpoint/bucket details.
            throw new StorageException("Cannot store object (" + e.getClass().getSimpleName() + ")", e);
        } finally {
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
        requireValid(key);
        try {
            // The returned stream is the HTTP response body, streamed lazily; the caller closes it.
            return s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (NoSuchKeyException e) {
            throw new ObjectNotFoundException("Object not found");
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new ObjectNotFoundException("Object not found");
            }
            throw new StorageException("Cannot read object (status " + e.statusCode() + ")", e);
        } catch (software.amazon.awssdk.core.exception.SdkException e) {
            throw new StorageException("Cannot read object (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    @Override
    public void delete(String key) {
        requireValid(key);
        try {
            // S3 DELETE of an absent key succeeds, matching "absent object is not an error".
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (software.amazon.awssdk.core.exception.SdkException e) {
            throw new StorageException("Cannot delete object (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    @Override
    public boolean exists(String key) {
        requireValid(key);
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw new StorageException("Cannot check object (status " + e.statusCode() + ")", e);
        } catch (software.amazon.awssdk.core.exception.SdkException e) {
            throw new StorageException("Cannot check object (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    /** Spring calls close() on shutdown (inferred destroy method) so HTTP connections are released. */
    @Override
    public void close() {
        s3.close();
    }

    private static void requireValid(String key) {
        if (!StorageKeys.isValid(key)) {
            // The key is not echoed: it could be attacker-influenced and end up in logs.
            throw new IllegalArgumentException("Invalid storage key");
        }
    }
}
