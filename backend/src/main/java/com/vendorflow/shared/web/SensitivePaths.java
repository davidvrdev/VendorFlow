package com.vendorflow.shared.web;

import java.util.regex.Pattern;

/**
 * Paths that carry a secret in a path segment (the RFC 8058 one-click unsubscribe URL of automated chasing, ADR-0012).
 * Everything that echoes or logs a request path (problem {@code instance}, log lines) must pass it through {@link #mask}.
 * Matching is case-insensitive and tolerant of ";params" and a trailing slash, like the router.
 */
public final class SensitivePaths {

    private static final Pattern ONE_CLICK =
            Pattern.compile("(?i)^(/api/v1/portal/chasing/one-click/)[^/?#]*([/?#].*)?$");

    private SensitivePaths() {
    }

    /** @return the path with the secret segment replaced by {@code {token}}; other paths unchanged (null stays null) */
    public static String mask(String path) {
        if (path == null) {
            return null;
        }
        var m = ONE_CLICK.matcher(path);
        return m.matches() ? m.group(1) + "{token}" : path;
    }
}
