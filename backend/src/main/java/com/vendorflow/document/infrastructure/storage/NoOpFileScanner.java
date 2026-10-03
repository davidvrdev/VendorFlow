package com.vendorflow.document.infrastructure.storage;

import java.io.InputStream;

/** Default scanner: accepts everything. Not acceptable in production (see {@link ScannerStartupCheck}). */
public final class NoOpFileScanner implements FileScanner {

    @Override
    public Result scan(InputStream content, String detectedMimeType) {
        return Result.ok();
    }
}
