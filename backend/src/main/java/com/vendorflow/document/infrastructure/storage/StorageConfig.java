package com.vendorflow.document.infrastructure.storage;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses the ObjectStorage implementation from {@code app.storage.provider} (filesystem | s3, ADR-0007/ADR-0010)
 * and the FileScanner from {@code vendorflow.storage.scanner} (noop | clamav).
 */
@Configuration(proxyBeanMethods = false)
public class StorageConfig {

    @Bean
    @ConditionalOnProperty(name = "app.storage.provider", havingValue = "filesystem", matchIfMissing = true)
    ObjectStorage filesystemObjectStorage(@Value("${app.storage.filesystem.root:./storage-data}") String root) {
        return new FilesystemObjectStorage(Path.of(root));
    }

    /**
     * S3-compatible storage (Supabase Storage in production, ADR-0010). Missing settings fail at startup with a clear
     * message (and ProfileGuard checks the same in prod), never at the first upload.
     */
    @Bean
    @ConditionalOnProperty(name = "app.storage.provider", havingValue = "s3")
    ObjectStorage s3ObjectStorage(@Value("${app.storage.s3.endpoint:}") String endpoint,
            @Value("${app.storage.s3.region:}") String region, @Value("${app.storage.s3.bucket:}") String bucket,
            @Value("${app.storage.s3.access-key-id:}") String accessKeyId,
            @Value("${app.storage.s3.secret-access-key:}") String secretAccessKey) {
        if (endpoint.isBlank() || region.isBlank() || bucket.isBlank() || accessKeyId.isBlank()
                || secretAccessKey.isBlank()) {
            throw new IllegalStateException("app.storage.provider=s3 needs STORAGE_S3_ENDPOINT, STORAGE_S3_REGION, "
                    + "STORAGE_S3_BUCKET, STORAGE_S3_ACCESS_KEY_ID and STORAGE_S3_SECRET_ACCESS_KEY.");
        }
        return new S3ObjectStorage(S3ClientFactory.create(endpoint, region, accessKeyId, secretAccessKey), bucket);
    }

    /** Default. {@code vendorflow.storage.scanner=noop}; an unknown value matches neither bean, so startup fails. */
    @Bean
    @ConditionalOnProperty(name = "vendorflow.storage.scanner", havingValue = "noop", matchIfMissing = true)
    FileScanner noOpFileScanner() {
        return new NoOpFileScanner();
    }

    @Bean
    @ConditionalOnProperty(name = "vendorflow.storage.scanner", havingValue = "clamav")
    FileScanner clamAvFileScanner(@Value("${vendorflow.storage.clamav.host:localhost}") String host,
            @Value("${vendorflow.storage.clamav.port:3310}") int port,
            @Value("${vendorflow.storage.clamav.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${vendorflow.storage.clamav.read-timeout-ms:30000}") int readTimeoutMs,
            @Value("${vendorflow.storage.clamav.chunk-size:65536}") int chunkSize,
            @Value("${app.documents.max-size:15MB}") org.springframework.util.unit.DataSize maxSize) {
        return new ClamAvFileScanner(host, port, connectTimeoutMs, readTimeoutMs, chunkSize, maxSize.toBytes());
    }
}
