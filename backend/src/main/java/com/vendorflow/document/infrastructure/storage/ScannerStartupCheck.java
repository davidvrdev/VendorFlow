package com.vendorflow.document.infrastructure.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Logs ONE warning at startup when the prod profile runs with the no-op malware scanner. Never fails startup. */
@Component
class ScannerStartupCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ScannerStartupCheck.class);

    private final Environment environment;
    private final FileScanner scanner;

    ScannerStartupCheck(Environment environment, FileScanner scanner) {
        this.environment = environment;
        this.scanner = scanner;
    }

    static boolean shouldWarn(boolean prodProfileActive, FileScanner scanner) {
        return prodProfileActive && scanner instanceof NoOpFileScanner;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (shouldWarn(environment.acceptsProfiles(Profiles.of("prod")), scanner)) {
            log.warn("No malware scanner configured: uploaded files are only checked by extension and magic bytes. "
                    + "Declare a FileScanner bean (e.g. ClamAV) before accepting real customer uploads.");
        }
    }
}
