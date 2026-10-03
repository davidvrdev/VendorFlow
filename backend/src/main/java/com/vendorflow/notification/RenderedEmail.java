package com.vendorflow.notification;

/** {@code replyTo} is optional (null = none). */
public record RenderedEmail(String subject, String textBody, String htmlBody, String replyTo) {

    public RenderedEmail(String subject, String textBody, String htmlBody) {
        this(subject, textBody, htmlBody, null);
    }
}
