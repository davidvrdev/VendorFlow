package com.vendorflow.shared.error;

import com.vendorflow.shared.web.RequestIdFilter;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** Factory for RFC 9457 problem bodies so every error has the same shape (type, title, status, requestId). */
public final class Problems {

    public static final String BASE = "https://vendorflow.app/problems/";

    private Problems() {
    }

    public static ProblemDetail of(HttpStatusCode status, String slug, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(BASE + slug));
        pd.setTitle(title);
        pd.setProperty(RequestIdFilter.MDC_KEY, MDC.get(RequestIdFilter.MDC_KEY));
        return pd;
    }

    public static ProblemDetail unauthorized() {
        return of(HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentication required",
                "Authentication is required to access this resource.");
    }

    public static ProblemDetail forbidden() {
        return of(HttpStatus.FORBIDDEN, "forbidden", "Access denied",
                "You do not have permission to perform this action.");
    }

    public static ProblemDetail tooManyRequests() {
        return of(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "Too many requests",
                "Too many requests. Please try again later.");
    }
}
