package com.vendorflow.document.infrastructure.storage;

/** The metadata says a file exists but the storage does not have it (data loss / misconfiguration: log at ERROR). */
public class ObjectNotFoundException extends RuntimeException {

    public ObjectNotFoundException(String message) {
        super(message);
    }
}
