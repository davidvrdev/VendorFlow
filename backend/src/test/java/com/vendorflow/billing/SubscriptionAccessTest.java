package com.vendorflow.billing;

import static com.vendorflow.billing.domain.SubscriptionStatus.ACTIVE;
import static com.vendorflow.billing.domain.SubscriptionStatus.CANCELED;
import static com.vendorflow.billing.domain.SubscriptionStatus.INCOMPLETE;
import static com.vendorflow.billing.domain.SubscriptionStatus.PAST_DUE;
import static com.vendorflow.billing.domain.SubscriptionStatus.TRIALING;
import static com.vendorflow.billing.domain.SubscriptionStatus.UNPAID;
import static org.assertj.core.api.Assertions.assertThat;

import com.vendorflow.billing.domain.SubscriptionAccess;
import com.vendorflow.billing.domain.SubscriptionStatus;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SubscriptionAccessTest {

    static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");

    @Test
    void activeAndPastDueAreWritable() {
        assertThat(SubscriptionAccess.isReadOnly(ACTIVE, null, NOW)).isFalse();
        assertThat(SubscriptionAccess.isReadOnly(PAST_DUE, null, NOW)).isFalse();
        // an old trial date does not matter once the status is not TRIALING
        assertThat(SubscriptionAccess.isReadOnly(ACTIVE, NOW.minus(Duration.ofDays(90)), NOW)).isFalse();
        assertThat(SubscriptionAccess.isReadOnly(PAST_DUE, NOW.minus(Duration.ofDays(90)), NOW)).isFalse();
    }

    @Test
    void trialIsWritableOnlyUntilItEnds() {
        assertThat(SubscriptionAccess.isReadOnly(TRIALING, NOW.plusSeconds(1), NOW)).isFalse();
        assertThat(SubscriptionAccess.isReadOnly(TRIALING, NOW.plus(Duration.ofDays(14)), NOW)).isFalse();
        // the end instant itself is already over
        assertThat(SubscriptionAccess.isReadOnly(TRIALING, NOW, NOW)).isTrue();
        assertThat(SubscriptionAccess.isReadOnly(TRIALING, NOW.minusSeconds(1), NOW)).isTrue();
    }

    @Test
    void trialWithoutEndDateFailsClosed() {
        assertThat(SubscriptionAccess.isReadOnly(TRIALING, null, NOW)).isTrue();
    }

    @Test
    void canceledUnpaidAndIncompleteAreReadOnly() {
        for (SubscriptionStatus status : new SubscriptionStatus[] {CANCELED, UNPAID, INCOMPLETE}) {
            assertThat(SubscriptionAccess.isReadOnly(status, NOW.plus(Duration.ofDays(30)), NOW)).as(status.name())
                    .isTrue();
            assertThat(SubscriptionAccess.isReadOnly(status, null, NOW)).as(status.name()).isTrue();
        }
    }

    @Test
    void stripeStatusMapping() {
        assertThat(SubscriptionStatus.fromStripe("active")).contains(ACTIVE);
        assertThat(SubscriptionStatus.fromStripe("trialing")).contains(TRIALING);
        assertThat(SubscriptionStatus.fromStripe("past_due")).contains(PAST_DUE);
        assertThat(SubscriptionStatus.fromStripe("canceled")).contains(CANCELED);
        assertThat(SubscriptionStatus.fromStripe("incomplete_expired")).contains(CANCELED);
        assertThat(SubscriptionStatus.fromStripe("unpaid")).contains(UNPAID);
        assertThat(SubscriptionStatus.fromStripe("paused")).contains(UNPAID);
        assertThat(SubscriptionStatus.fromStripe("incomplete")).contains(INCOMPLETE);
        assertThat(SubscriptionStatus.fromStripe("something_new")).isEmpty();
        assertThat(SubscriptionStatus.fromStripe(null)).isEmpty();
    }
}
