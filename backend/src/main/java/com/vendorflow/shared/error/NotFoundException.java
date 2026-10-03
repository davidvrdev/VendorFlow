package com.vendorflow.shared.error;

/** Resource missing OR not visible to the caller's organization: both look identical to the client (404). */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
