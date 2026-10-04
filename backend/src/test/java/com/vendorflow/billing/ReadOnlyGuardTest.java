package com.vendorflow.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.reminder.ReminderService;
import com.vendorflow.support.ApiClient;
import com.vendorflow.support.TestAccounts.Account;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

/** One central 402 mechanism: a representative write per feature, plus everything that must stay open. */
class ReadOnlyGuardTest extends BillingTestBase {

    @Autowired ReminderService reminders;

    static final String ID = UUID.randomUUID().toString();

    Account lapsedOwner() throws Exception {
        Account owner = accounts.signup("Lapsed " + UUID.randomUUID());
        setStatus(owner.organizationId(), "CANCELED");
        return owner;
    }

    @Test
    void mutatingRequestsOfEveryFeatureAnswer402SubscriptionInactive() throws Exception {
        Account owner = lapsedOwner();
        ApiClient c = owner.client();
        String[] titleCheck = {"Subscription inactive"};

        // organization
        c.patch("/api/v1/organization", Map.of("name", "x")).andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.title").value(titleCheck[0]))
                .andExpect(jsonPath("$.type").value("https://vendorflow.app/problems/subscription-inactive"))
                .andExpect(jsonPath("$.status").value(402))
                .andExpect(jsonPath("$.requestId").exists());
        c.post("/api/v1/organization/invitations", Map.of("email", "a@example.com", "role", "MEMBER"))
                .andExpect(status().isPaymentRequired());
        c.patch("/api/v1/organization/members/" + ID, Map.of("role", "ADMIN")).andExpect(status().isPaymentRequired());
        c.delete("/api/v1/organization/invitations/" + ID).andExpect(status().isPaymentRequired());
        // vendor
        c.post("/api/v1/vendors", Map.of("name", "V")).andExpect(status().isPaymentRequired());
        c.put("/api/v1/vendors/" + ID, Map.of("name", "V")).andExpect(status().isPaymentRequired());
        c.post("/api/v1/vendors/" + ID + "/deactivate", null).andExpect(status().isPaymentRequired());
        c.put("/api/v1/vendors/" + ID + "/requirements", Map.of()).andExpect(status().isPaymentRequired());
        c.post("/api/v1/vendors/" + ID + "/document-requests", Map.of()).andExpect(status().isPaymentRequired());
        // vendor CSV import (the export is a GET and stays open, see below)
        c.postMultipart("/api/v1/vendors/import/preview",
                new MockMultipartFile("file", "v.csv", "text/csv", "name\nA".getBytes()), Map.of())
                .andExpect(status().isPaymentRequired());
        c.post("/api/v1/vendors/import/" + ID + "/commit", null).andExpect(status().isPaymentRequired());
        // document
        c.postMultipart("/api/v1/vendors/" + ID + "/documents",
                new MockMultipartFile("file", "a.pdf", "application/pdf", "%PDF-1.4".getBytes()), Map.of())
                .andExpect(status().isPaymentRequired());
        c.patch("/api/v1/documents/" + ID, Map.of()).andExpect(status().isPaymentRequired());
        c.post("/api/v1/documents/" + ID + "/review", Map.of()).andExpect(status().isPaymentRequired());
        c.post("/api/v1/documents/" + ID + "/archive", null).andExpect(status().isPaymentRequired());
        // document types
        c.post("/api/v1/document-types", Map.of("name", "T")).andExpect(status().isPaymentRequired());
        c.patch("/api/v1/document-types/" + ID, Map.of()).andExpect(status().isPaymentRequired());
    }

    @Test
    void readsDownloadsAndCsvExportStayOpen() throws Exception {
        Account owner = lapsedOwner();
        ApiClient c = owner.client();
        c.get("/api/v1/organization").andExpect(status().isOk());
        c.get("/api/v1/vendors").andExpect(status().isOk());
        c.get("/api/v1/document-types").andExpect(status().isOk());
        c.get("/api/v1/dashboard/summary").andExpect(status().is(org.hamcrest.Matchers.not(402)));
        c.get("/api/v1/vendors/export.csv").andExpect(status().isOk());
        c.get("/api/v1/vendors/import/template.csv").andExpect(status().isOk());
        c.get("/api/v1/organization/members").andExpect(status().isOk());
        c.get("/api/v1/billing/subscription").andExpect(status().isOk())
                .andExpect(jsonPath("$.readOnly").value(true));
        c.get("/api/v1/me").andExpect(status().isOk());
    }

    @Test
    void waysOutAndAccountEndpointsStayOpen() throws Exception {
        Account owner = lapsedOwner();
        ApiClient c = owner.client();
        c.post("/api/v1/billing/checkout-session", null).andExpect(status().isOk());
        c.post("/api/v1/billing/portal-session", null).andExpect(status().isOk());
        // switching organization works (the target may be a healthy one)
        Account other = accounts.signup("Healthy " + UUID.randomUUID());
        accounts.joinOrganization(owner, other.organizationId(), "MEMBER");
        owner.client().patch("/api/v1/organization", Map.of("name", "Healthy renamed"))
                .andExpect(status().isForbidden()); // MEMBER of the healthy org: 403 from authz, NOT 402
        c.post("/api/v1/session/organization", Map.of("organizationId", owner.organizationId()))
                .andExpect(status().isOk());
        c.post("/api/v1/auth/logout", null).andExpect(status().is2xxSuccessful());
    }

    @Test
    void leavingTheOrganizationStaysPossibleWhileReadOnly() throws Exception {
        Account owner = lapsedOwner();
        Account member = accounts.memberOf(owner.organizationId(), "MEMBER", "Lea Leaver");
        String membershipId = accounts.membershipId(owner.organizationId(), member.userId());
        member.client().delete("/api/v1/organization/members/" + membershipId).andExpect(status().isNoContent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "PAST_DUE"})
    void activeAndPastDueOrganizationsAreWritable(String subscriptionStatus) throws Exception {
        Account owner = accounts.signup("Writable " + subscriptionStatus);
        setStatus(owner.organizationId(), subscriptionStatus);
        owner.client().patch("/api/v1/organization", Map.of("name", "Writable " + subscriptionStatus + " ok"))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELED", "UNPAID", "INCOMPLETE"})
    void inactiveStatusesAreReadOnly(String subscriptionStatus) throws Exception {
        Account owner = accounts.signup("Inactive " + subscriptionStatus);
        setStatus(owner.organizationId(), subscriptionStatus);
        owner.client().patch("/api/v1/organization", Map.of("name", "nope")).andExpect(status().isPaymentRequired());
    }

    @Test
    void enforcementIsPerOrganizationAndRecoversWhenTheSubscriptionIsActiveAgain() throws Exception {
        Account lapsed = lapsedOwner();
        Account healthy = accounts.signup("Healthy " + UUID.randomUUID());
        healthy.client().patch("/api/v1/organization", Map.of("name", "Healthy 2")).andExpect(status().isOk());
        lapsed.client().patch("/api/v1/organization", Map.of("name", "x")).andExpect(status().isPaymentRequired());

        setStatus(lapsed.organizationId(), "ACTIVE");
        lapsed.client().patch("/api/v1/organization", Map.of("name", "Back")).andExpect(status().isOk());
    }

    @Test
    void roleChecksStillApplyBeforeAndAfterTheGuardForActiveOrganizations() throws Exception {
        Account owner = accounts.signup("Authz " + UUID.randomUUID());
        setStatus(owner.organizationId(), "ACTIVE");
        Account viewer = accounts.memberOf(owner.organizationId(), "VIEWER", "Vic Viewer");
        viewer.client().patch("/api/v1/organization", Map.of("name", "no")).andExpect(status().isForbidden());
    }

    @Test
    void remindersSkipReadOnlyOrganizationsAndRunForTheOthers() throws Exception {
        Account lapsed = lapsedOwner();
        Account healthy = accounts.signup("Healthy reminders " + UUID.randomUUID());
        setStatus(healthy.organizationId(), "ACTIVE");
        // org time zone is America/New_York: move the clock to 12:00 there so the 07:00 gate is open
        ZoneId ny = ZoneId.of("America/New_York");
        Instant noon = LocalDate.ofInstant(clock.instant(), ny).atTime(12, 0).atZone(ny).toInstant();
        clock.advance(Duration.between(clock.instant(), noon));

        reminders.runDue();

        assertThat(lastRun(lapsed.organizationId())).isNull();
        assertThat(lastRun(healthy.organizationId())).isNotNull();
    }

    private Object lastRun(String organizationId) {
        return jdbc.queryForObject("select last_reminder_run_date from organization where id = ?::uuid",
                java.sql.Date.class, organizationId);
    }
}
