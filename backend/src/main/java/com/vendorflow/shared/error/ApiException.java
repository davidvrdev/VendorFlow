package com.vendorflow.shared.error;

import org.springframework.http.HttpStatus;

/**
 * A business-rule failure with an explicit status/title that the client may display (409 "Email already
 * registered", 422 ..., 403 "No active organization"). Messages are written by us, never taken from other exceptions.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String slug;
    private final String title;
    private final Long retryAfterSeconds;

    public ApiException(HttpStatus status, String slug, String title, String detail) {
        this(status, slug, title, detail, null);
    }

    /** {@code retryAfterSeconds} (nullable) becomes a Retry-After header (429). */
    public ApiException(HttpStatus status, String slug, String title, String detail, Long retryAfterSeconds) {
        super(detail);
        this.retryAfterSeconds = retryAfterSeconds;
        this.status = status;
        this.slug = slug;
        this.title = title;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public HttpStatus status() {
        return status;
    }

    public String slug() {
        return slug;
    }

    public String title() {
        return title;
    }
}
