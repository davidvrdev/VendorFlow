package com.vendorflow.document.infrastructure.storage;

/**
 * The scanner could not give a verdict (unreachable, timed out, protocol error). Callers must FAIL CLOSED: an unscanned
 * file is never accepted. The message is for logs only.
 */
public class ScannerUnavailableException extends RuntimeException {

    public ScannerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public ScannerUnavailableException(String message) {
        super(message);
    }
}
