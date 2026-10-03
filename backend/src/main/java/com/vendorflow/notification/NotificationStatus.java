package com.vendorflow.notification;

/** Matches the CHECK constraint on notification.status. SENDING is reserved (rows are locked while sending). */
public enum NotificationStatus {
    PENDING, SENDING, SENT, FAILED, DEAD
}
