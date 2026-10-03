package com.vendorflow.document.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ScannerStartupCheckTest {

    @Test
    void warnsOnlyForProdWithTheNoOpScanner() {
        FileScanner real = (content, mime) -> FileScanner.Result.ok();
        assertThat(ScannerStartupCheck.shouldWarn(true, new NoOpFileScanner())).isTrue();
        assertThat(ScannerStartupCheck.shouldWarn(false, new NoOpFileScanner())).isFalse();
        assertThat(ScannerStartupCheck.shouldWarn(true, real)).isFalse();
    }
}
