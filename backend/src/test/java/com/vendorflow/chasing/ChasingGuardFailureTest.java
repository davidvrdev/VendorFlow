package com.vendorflow.chasing;

import static org.assertj.core.api.Assertions.assertThat;

import com.vendorflow.notification.DeliveryGuard;
import com.vendorflow.notification.NotificationKind;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** A delivery guard that throws for one row must fail that row only, not abort the batch (the other row is sent). */
@Import(ChasingGuardFailureTest.ThrowingGuardConfig.class)
class ChasingGuardFailureTest extends ChasingTestBase {

    static volatile String poisonedVendorId;

    @TestConfiguration(proxyBeanMethods = false)
    static class ThrowingGuardConfig {
        @Bean
        DeliveryGuard throwingGuard() {
            return (NotificationKind kind, UUID organizationId, Map<String, Object> payload) -> {
                if (poisonedVendorId != null && poisonedVendorId.equals(String.valueOf(payload.get("vendorId")))) {
                    throw new IllegalStateException("boom");
                }
                return null;
            };
        }
    }

    @Test
    void aThrowingGuardFailsOnlyItsOwnRow() throws Exception {
        enableDefaults();
        String bad = email("bad");
        String good = email("good");
        UUID badVendor = missingVendor(bad);
        UUID goodVendor = missingVendor(good);
        clockTo(NY, today(NY), LocalTime.of(10, 0));
        assertThat(run().chased()).isEqualTo(2);
        poisonedVendorId = badVendor.toString();
        try {
            dispatcher.dispatchBatch();
        } finally {
            poisonedVendorId = null;
        }
        assertThat(status(badVendor)).isIn("PENDING", "FAILED", "RETRY", "DEAD").isNotEqualTo("SENT");
        assertThat(status(goodVendor)).isEqualTo("SENT");
    }

    private String status(UUID vendor) {
        return jdbc.queryForObject("select status from notification where organization_id = ?::uuid and "
                + "kind = 'VENDOR_CHASE' and payload ->> 'vendorId' = ?", String.class, org, vendor.toString());
    }
}
