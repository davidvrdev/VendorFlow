package com.vendorflow.notification;

import java.util.UUID;

/**
 * A rendered email handed to an EmailSender. toString is overridden because the body can contain a secret link:
 * an accidental log of this object must not leak it. {@code kind} lets test senders (e2e mailbox) classify messages;
 * it is null for messages built without it.
 */
public record EmailMessage(UUID notificationId, String idempotencyKey, String to, String subject, String textBody,
        String htmlBody, NotificationKind kind) {

    public EmailMessage(UUID notificationId, String idempotencyKey, String to, String subject, String textBody,
            String htmlBody) {
        this(notificationId, idempotencyKey, to, subject, textBody, htmlBody, null);
    }

    @Override
    public String toString() {
        return "EmailMessage[notificationId=" + notificationId + "]";
    }
}
