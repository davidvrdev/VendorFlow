package com.vendorflow.document.infrastructure.storage;

/** I/O failure in the storage backend. Messages never contain file contents. */
public class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
