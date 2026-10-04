package com.vendorflow.chasing.application;

import com.vendorflow.chasing.infrastructure.ChasingStore;
import com.vendorflow.notification.DeliveryGuard;
import com.vendorflow.notification.NotificationKind;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Dispatcher backstop (L1): a chase row that was enqueued before the vendor paused or opted out (a race the claim-time
 * check and the cancel-on-pause cannot fully close) is dropped at send time instead of emailed.
 */
@Component
class ChasingDeliveryGuard implements DeliveryGuard {

    private final ChasingStore store;

    ChasingDeliveryGuard(ChasingStore store) {
        this.store = store;
    }

    @Override
    public String blockReason(NotificationKind kind, UUID organizationId, Map<String, Object> payload) {
        if (kind != NotificationKind.VENDOR_CHASE || organizationId == null) {
            return null;
        }
        Object vendor = payload.get("vendorId");
        if (vendor == null) {
            return null;
        }
        try {
            return store.isPaused(organizationId, UUID.fromString(vendor.toString())) ? "Vendor chasing paused" : null;
        } catch (IllegalArgumentException e) {
            return null; // malformed id: nothing to look up, let it go through the normal path
        }
    }
}
