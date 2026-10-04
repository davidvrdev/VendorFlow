package com.vendorflow.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vendorflow.shared.profile.ProfileGuard;
import org.junit.jupiter.api.Test;

/** Prod must not start with billing on and keys missing (mirrors the Resend/logging provider refusal). */
class BillingProfileGuardTest {

    @Test
    void prodWithBillingEnabledRefusesMissingKeys() {
        assertThatThrownBy(() -> ProfileGuard.checkBilling(true, false, false, "", "whsec_x", "price_x"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("STRIPE_SECRET_KEY");
        assertThatThrownBy(() -> ProfileGuard.checkBilling(true, false, false, "sk_live_x", null, "price_x"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ProfileGuard.checkBilling(true, false, false, "sk_live_x", "whsec_x", "  "))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void errorMessageNeverContainsTheKeys() {
        assertThatThrownBy(() -> ProfileGuard.checkBilling(true, false, false, "sk_live_SECRET123", "", "price_x"))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("SECRET123"));
    }

    @Test
    void completeConfigurationOrBillingDisabledStarts() {
        assertThatCode(() -> ProfileGuard.checkBilling(true, false, false, "sk_live_x", "whsec_x", "price_x"))
                .doesNotThrowAnyException();
        assertThatCode(() -> ProfileGuard.checkBilling(false, true, false, "", "", "")).doesNotThrowAnyException();
    }

    @Test
    void disabledBillingInProdNeedsExplicitOptIn() {
        assertThatThrownBy(() -> ProfileGuard.checkBilling(false, false, false, "", "", ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("allow-disabled-in-prod");
    }

    @Test
    void testKeysInProdAreRejectedUnlessOptedIn() {
        assertThatThrownBy(() -> ProfileGuard.checkBilling(true, false, false, "sk_test_SECRET123", "whsec_x", "price_x"))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("SECRET123"))
                .hasMessageContaining("allow-test-keys-in-prod");
        assertThatThrownBy(() -> ProfileGuard.checkBilling(true, false, false, "sk_live_x", "bad_WHSECRET", "price_x"))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("WHSECRET"));
        assertThatCode(() -> ProfileGuard.checkBilling(true, false, true, "sk_test_x", "whsec_x", "price_x"))
                .doesNotThrowAnyException();
        assertThatCode(() -> ProfileGuard.checkBilling(true, false, false, "rk_live_x", "whsec_x", "price_x"))
                .doesNotThrowAnyException();
    }
}
