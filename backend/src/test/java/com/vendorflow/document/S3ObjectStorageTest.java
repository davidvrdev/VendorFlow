package com.vendorflow.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vendorflow.document.infrastructure.storage.ObjectNotFoundException;
import com.vendorflow.document.infrastructure.storage.S3ClientFactory;
import com.vendorflow.document.infrastructure.storage.S3ObjectStorage;
import com.vendorflow.document.infrastructure.storage.StorageException;
import com.vendorflow.document.infrastructure.storage.StorageKeys;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/**
 * S3ObjectStorage against a REAL S3 protocol endpoint (LocalStack S3 in Testcontainers), path-style like Supabase.
 * ADR-0007 required this before production: the S3 client is not mocked.
 */
class S3ObjectStorageTest {

    // LocalStack (S3 service only) speaks the real S3 protocol. MinIO was the first choice but its images are no longer
    // pullable from Docker Hub/quay. Pinned: 3.8 is the last line that needs no auth token.
    static GenericContainer<?> s3 = new GenericContainer<>("localstack/localstack:3.8").withEnv("SERVICES", "s3")
            .withExposedPorts(4566).waitingFor(Wait.forHttp("/_localstack/health").forPort(4566));
    static String url;
    static S3ObjectStorage storage;
    static S3Client admin;
    static final String BUCKET = "documents";

    @BeforeAll
    static void start() {
        s3.start();
        url = "http://" + s3.getHost() + ":" + s3.getMappedPort(4566);
        admin = S3ClientFactory.create(url, "us-east-1", "test", "test");
        admin.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        storage = new S3ObjectStorage(
                S3ClientFactory.create(url, "us-east-1", "test", "test"), BUCKET);
    }

    @AfterAll
    static void stop() {
        storage.close();
        admin.close();
        s3.stop();
    }

    static InputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    static String key() {
        return StorageKeys.documentKey(UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void putGetExistsDeleteRoundTrip() throws IOException {
        String key = key();
        assertThat(storage.exists(key)).isFalse();
        storage.put(key, bytes("hello"), 5);
        assertThat(storage.exists(key)).isTrue();
        try (InputStream in = storage.get(key)) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
        }
        storage.delete(key);
        assertThat(storage.exists(key)).isFalse();
        storage.delete(key); // absent object is not an error
    }

    @Test
    void sizeHintIsNotTrusted() throws IOException {
        String key = key();
        storage.put(key, bytes("twelve bytes"), -1);
        try (InputStream in = storage.get(key)) {
            assertThat(in.readAllBytes()).hasSize(12);
        }
        storage.put(key, bytes("abc"), 9999); // overwrite with a wrong hint
        try (InputStream in = storage.get(key)) {
            assertThat(in.readAllBytes()).hasSize(3);
        }
    }

    @Test
    void largeObjectIsStoredIntact() throws IOException {
        byte[] data = new byte[15 * 1024 * 1024];
        new Random(42).nextBytes(data);
        String key = key();
        storage.put(key, new ByteArrayInputStream(data), data.length);
        try (InputStream in = storage.get(key)) {
            assertThat(in.readAllBytes()).isEqualTo(data);
        }
    }

    @Test
    void getOfMissingObjectIsNotFound() {
        assertThatThrownBy(() -> storage.get(key())).isInstanceOf(ObjectNotFoundException.class);
    }

    @Test
    void failedReadLeavesNoObject() {
        String key = key();
        InputStream failing = new InputStream() {
            int n;

            @Override
            public int read() throws IOException {
                if (n++ > 10) {
                    throw new IOException("boom");
                }
                return 'x';
            }
        };
        assertThatThrownBy(() -> storage.put(key, failing, -1)).isInstanceOf(StorageException.class);
        assertThat(storage.exists(key)).isFalse();
    }

    @Test
    void invalidKeysAreRejectedBeforeAnyRequest() {
        for (String bad : new String[] {"../etc/passwd", "org/A/doc/B", "/org/x", "a b", "", null}) {
            assertThatThrownBy(() -> storage.put(bad, bytes("x"), 1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.get(bad)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.exists(bad)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.delete(bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void unreachableOrMissingBucketIsAStorageException() {
        S3ObjectStorage wrong = new S3ObjectStorage(
                S3ClientFactory.create(url, "us-east-1", "test", "test"),
                "no-such-bucket");
        assertThatThrownBy(() -> wrong.put(key(), bytes("x"), 1)).isInstanceOf(StorageException.class);
        wrong.close();
    }
}
