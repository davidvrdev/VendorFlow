package com.vendorflow.document.infrastructure.storage;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses the ObjectStorage implementation from {@code app.storage.provider} (S3 comes before Phase 11, ADR-0007)
 * and provides the default no-op FileScanner (replaced by declaring any other FileScanner bean).
 */
@Configuration(proxyBeanMethods = false)
public class StorageConfig {

    @Bean
    @ConditionalOnProperty(name = "app.storage.provider", havingValue = "filesystem", matchIfMissing = true)
    ObjectStorage filesystemObjectStorage(@Value("${app.storage.filesystem.root:./storage-data}") String root) {
        return new FilesystemObjectStorage(Path.of(root));
    }

    @Bean
    @ConditionalOnMissingBean(FileScanner.class)
    FileScanner noOpFileScanner() {
        return new NoOpFileScanner();
    }
}
