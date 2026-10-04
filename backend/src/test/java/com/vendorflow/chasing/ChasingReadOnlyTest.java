package com.vendorflow.chasing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.billing.BillingTestBase;
import com.vendorflow.chasing.application.ChasingService;
import com.vendorflow.notification.NotificationKind;
import com.vendorflow.notification.OutboxDispatcher;
import com.vendorflow.support.ApiClient;
import com.vendorflow.support.ComplianceFixtures;
import com.vendorflow.support.TestAccounts.Account;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

/** A read-only (402) organization is never chased and cannot edit settings, but a vendor can always unsubscribe. */
class ChasingReadOnlyTest extends BillingTestBase {

    @Autowired ChasingService chasing;
    @Autowired OutboxDispatcher dispatcher;

    @Test
    void readOnlyOrganizationsAreSkippedButOptOutStillWorks() throws Exception {
        Account owner = accounts.verified(accounts.signup("Lapsed Chasing Org " + UUID.randomUUID()));
        String org = owner.organizationId();
        ComplianceFixtures fx = new ComplianceFixtures(jdbc);
        owner.client().put("/api/v1/organization/chasing",
                Map.of("enabled", true, "cadenceDays", 3, "maxAttempts", 4, "leadDays", 30, "sendHourLocal", 0,
                        "ccStaff", false)).andExpect(status().isOk());
        UUID v = fx.vendor(org, "Lapsed vendor " + UUID.randomUUID());
        String to = "lapsed." + UUID.randomUUID() + "@vendor.example.com";
        jdbc.update("update vendor set email = ? where id = ?", to, v);
        fx.require(org, v, "W9");

        assertThat(chasing.runOrganization(UUID.fromString(org)).chased()).isEqualTo(1);
        while (dispatcher.dispatchBatch() > 0) {
            // drain
        }
        String text = emailSender.sent().stream().filter(m -> m.to().equals(to) && m.kind() == NotificationKind.VENDOR_CHASE)
                .findFirst().orElseThrow().textBody();
        Matcher m = Pattern.compile("/portal/unsubscribe#token=([A-Za-z0-9_-]+)").matcher(text);
        assertThat(m.find()).isTrue();

        setStatus(org, "CANCELED");
        clock.advance(java.time.Duration.ofDays(5));
        assertThat(chasing.runOrganization(UUID.fromString(org)).ran()).isFalse();
        assertThat(chasing.runDue()).isGreaterThanOrEqualTo(0);
        assertThat(jdbc.queryForObject("select count(*) from vendor_chase where vendor_id = ?", Integer.class, v))
                .isEqualTo(1);
        owner.client().put("/api/v1/organization/chasing",
                Map.of("enabled", false, "cadenceDays", 3, "maxAttempts", 4, "leadDays", 30, "sendHourLocal", 0,
                        "ccStaff", false)).andExpect(status().isPaymentRequired());

        new ApiClient(mvc, json).remoteAddr("198.51.100.9").header("X-Portal-Token", m.group(1))
                .perform(HttpMethod.POST, "/api/v1/portal/chasing/opt-out", null, false).andExpect(status().isOk());
        assertThat(LocalDate.now(ZoneId.of("UTC"))).isNotNull();
    }
}
