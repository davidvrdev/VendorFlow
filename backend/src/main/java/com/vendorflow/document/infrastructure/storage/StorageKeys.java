package com.vendorflow.document.infrastructure.storage;

import java.util.UUID;
import java.util.regex.Pattern;

/** Storage key format. Keys are built here from UUIDs only; user input can never reach them. */
public final class StorageKeys {

    /** Segments of lower-case letters, digits and '-', separated by single '/'. No dots, no leading/trailing '/'. */
    private static final Pattern VALID = Pattern.compile("[a-z0-9-]{1,64}(?:/[a-z0-9-]{1,64}){0,7}");
    private static final int MAX_LENGTH = 200;

    private StorageKeys() {
    }

    public static String documentKey(UUID organizationId, UUID documentId) {
        return "org/" + organizationId + "/doc/" + documentId;
    }

    public static boolean isValid(String key) {
        return key != null && key.length() <= MAX_LENGTH && VALID.matcher(key).matches();
    }
}
