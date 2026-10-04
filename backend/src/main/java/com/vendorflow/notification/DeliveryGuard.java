package com.vendorflow.notification;

import java.util.Map;
import java.util.UUID;

/**
 * Last-moment veto on delivery, asked by the dispatcher right before a notification is sent. Features implement it for
 * their own kinds (chasing: vendor paused or opted out since the row was enqueued) without the notification package
 * knowing about them. Implementations must be cheap and must not log the payload.
 */
public interface DeliveryGuard {

    /** @return a short sanitized reason to mark the notification DEAD (never sent), or null to let it go out */
    String blockReason(NotificationKind kind, UUID organizationId, Map<String, Object> payload);
}
