package com.vendorflow.notification;

import java.util.Map;
import java.util.UUID;

/**
 * A rendered email handed to an EmailSender. toString is overridden because the body can contain a secret link:
 * an accidental log of this object must not leak it. {@code kind} lets test senders (e2e mailbox) classify messages;
 * it is null for messages built without it. {@code replyTo} is optional (document requests). {@code headers} are extra
 * RFC 5322 headers (List-Unsubscribe...); never null.
 */
public record EmailMessage(UUID notificationId, String idempotencyKey, String to, String subject, String textBody,
        String htmlBody, NotificationKind kind, String replyTo, Map<String, String> headers) {

    public EmailMessage {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public EmailMessage(UUID notificationId, String idempotencyKey, String to, String subject, String textBody,
            String htmlBody, NotificationKind kind, String replyTo) {
        this(notificationId, idempotencyKey, to, subject, textBody, htmlBody, kind, replyTo, Map.of());
    }

    public EmailMessage(UUID notificationId, String idempotencyKey, String to, String subject, String textBody,
            String htmlBody, NotificationKind kind) {
        this(notificationId, idempotencyKey, to, subject, textBody, htmlBody, kind, null, Map.of());
    }

    public EmailMessage(UUID notificationId, String idempotencyKey, String to, String subject, String textBody,
            String htmlBody) {
        this(notificationId, idempotencyKey, to, subject, textBody, htmlBody, null, null, Map.of());
    }

    @Override
    public String toString() {
        return "EmailMessage[notificationId=" + notificationId + "]";
    }
}
