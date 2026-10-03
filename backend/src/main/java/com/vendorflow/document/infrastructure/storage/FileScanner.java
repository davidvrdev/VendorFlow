package com.vendorflow.document.infrastructure.storage;

import java.io.InputStream;

/**
 * Hook point for malware scanning, called with the uploaded bytes BEFORE anything is stored. The MVP ships
 * a no-op scanner (see StorageConfig); a real scanner (ClamAV...) replaces it without touching the upload pipeline.
 */
public interface FileScanner {

    /** Verdict of a scan. {@code reason} is for logs only and is never sent to the client. */
    record Result(boolean clean, String reason) {

        public static Result ok() {
            return new Result(true, null);
        }

        public static Result rejected(String reason) {
            return new Result(false, reason);
        }
    }

    /** The scanner gets a fresh stream of the whole file and may close it. */
    Result scan(InputStream content, String detectedMimeType);
}
