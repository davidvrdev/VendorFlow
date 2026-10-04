package com.vendorflow.notification;

/** Matches the CHECK constraint on notification.kind. */
public enum NotificationKind {
    INVITATION, EMAIL_VERIFICATION, PASSWORD_RESET, COMPLIANCE_DIGEST, DOCUMENT_REQUEST, PASSWORD_CHANGED
}
