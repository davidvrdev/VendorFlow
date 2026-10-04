package com.vendorflow.billing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.stripe.model.Subscription;
import com.stripe.net.ApiResource;
import com.vendorflow.billing.application.RemoteSubscription;
import com.vendorflow.billing.domain.SubscriptionStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The Stripe SDK object to neutral snapshot mapping (period end lives on the items in current API versions). */
class StripeSubscriptionMappingTest {

    @Test
    void mapsStatusPeriodEndAndCancelFlag() {
        Subscription s = ApiResource.GSON.fromJson("""
                {"id":"sub_1","object":"subscription","customer":"cus_1","status":"past_due",
                 "cancel_at_period_end":true,
                 "items":{"object":"list","data":[
                   {"id":"si_1","object":"subscription_item","current_period_end":1790000000},
                   {"id":"si_2","object":"subscription_item","current_period_end":1780000000}]}}""",
                Subscription.class);
        RemoteSubscription r = StripeBillingGateway.toRemote(s);
        assertThat(r.id()).isEqualTo("sub_1");
        assertThat(r.customerId()).isEqualTo("cus_1");
        assertThat(r.status()).isEqualTo(SubscriptionStatus.PAST_DUE);
        assertThat(r.cancelAtPeriodEnd()).isTrue();
        assertThat(r.currentPeriodEnd()).isEqualTo(Instant.ofEpochSecond(1780000000L));
    }

    @Test
    void unknownStatusAndMissingItemsAreTolerated() {
        Subscription s = ApiResource.GSON.fromJson(
                "{\"id\":\"sub_2\",\"object\":\"subscription\",\"customer\":\"cus_2\",\"status\":\"new_thing\"}",
                Subscription.class);
        RemoteSubscription r = StripeBillingGateway.toRemote(s);
        assertThat(r.status()).isNull();
        assertThat(r.currentPeriodEnd()).isNull();
        assertThat(r.cancelAtPeriodEnd()).isFalse();
    }
}
