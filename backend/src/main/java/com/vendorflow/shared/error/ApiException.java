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

    public ApiException(HttpStatus status, String slug, String title, String detail) {
        super(detail);
        this.status = status;
        this.slug = slug;
        this.title = title;
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
