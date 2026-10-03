package com.vendorflow.notification;

import java.time.Instant;
import java.util.UUID;

/** API shape "NotificationView". Never exposes the payload (it can hold names, and for invitations a token). */
public record NotificationView(UUID id, NotificationKind kind, String recipientEmail, NotificationStatus status,
        int attempts, String lastError, Instant createdAt, Instant sentAt) {

    static NotificationView from(Notification n) {
        return new NotificationView(n.getId(), n.getKind(), n.getRecipientEmail(), n.getStatus(), n.getAttempts(),
                n.getLastError(), n.getCreatedAt(), n.getSentAt());
    }
}
