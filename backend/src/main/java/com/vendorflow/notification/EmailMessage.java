package com.vendorflow.notification;

import java.util.UUID;

/**
 * A rendered email handed to an EmailSender. toString is overridden because the body can contain a secret link:
 * an accidental log of this object must not leak it.
 */
public record EmailMessage(UUID notificationId, String idempotencyKey, String to, String subject, String textBody,
        String htmlBody) {

    @Override
    public String toString() {
        return "EmailMessage[notificationId=" + notificationId + "]";
    }
}
