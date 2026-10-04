package com.vendorflow.notification;

import java.util.Map;

/** {@code replyTo} is optional (null = none); {@code headers} are extra email headers (never null, usually empty). */
public record RenderedEmail(String subject, String textBody, String htmlBody, String replyTo,
        Map<String, String> headers) {

    public RenderedEmail {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public RenderedEmail(String subject, String textBody, String htmlBody, String replyTo) {
        this(subject, textBody, htmlBody, replyTo, Map.of());
    }

    public RenderedEmail(String subject, String textBody, String htmlBody) {
        this(subject, textBody, htmlBody, null, Map.of());
    }
}
