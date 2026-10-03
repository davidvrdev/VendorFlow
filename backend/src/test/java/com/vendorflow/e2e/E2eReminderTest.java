package com.vendorflow.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.compliance.application.ComplianceContextService;
import com.vendorflow.document.domain.ReviewStatus;
import com.vendorflow.notification.EmailTemplates;
import com.vendorflow.notification.NotificationRepository;
import com.vendorflow.notification.OutboxDispatcher;
import com.vendorflow.support.ApiClient;
import com.vendorflow.support.ComplianceFixtures;
import com.vendorflow.support.IntegrationTest;
import com.vendorflow.support.TestAccounts;
import com.vendorflow.support.TestAccounts.Account;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Profile e2e: the reminder endpoint exists, is guarded, and digests / document requests reach the mailbox. */
@ActiveProfiles({"test", "e2e"})
class E2eReminderTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired InMemoryMailbox mailbox;
    @Autowired NotificationRepository repository;
    @Autowired EmailTemplates templates;
    @Autowired PlatformTransactionManager txManager;
    @Autowired ComplianceContextService contexts;

    /** In this profile the CapturingEmailSender is @Primary; the real e2e wiring dispatches into the mailbox. */
    OutboxDispatcher mailboxDispatcher() {
        return new OutboxDispatcher(repository, mailbox, templates, txManager, clock, 20);
    }

    @Test
    void runEndpointProducesADigestInTheMailboxIgnoringTheTimeGate() throws Exception {
        TestAccounts accounts = new TestAccounts(mvc, json, jdbc);
        Account owner = accounts.verified(accounts.signup(TestAccounts.uniqueEmail(), TestAccounts.PASSWORD,
                "Dora Owner", "E2E Org " + UUID.randomUUID()));
        String org = owner.organizationId();
        ComplianceFixtures fx = new ComplianceFixtures(jdbc);
        UUID vendor = fx.vendor(org, "Mailbox Plumbing");
        fx.requirement(org, vendor, "COI", ReviewStatus.APPROVED, contexts.todayIn("America/New_York").plusDays(5));
        // 03:00 in New York would be before the 07:00 gate for a scheduled run
        jdbc.update("update organization set time_zone = 'America/New_York' where id = ?::uuid", org);

        owner.client().remoteAddr("127.0.0.1").post("/api/test/reminders/run", Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.ran").value(true)).andExpect(jsonPath("$.newLedgerRows").value(3))
                .andExpect(jsonPath("$.digestsEnqueued").value(1));

        jdbc.update("delete from notification where organization_id <> ?::uuid or organization_id is null", org);
        mailboxDispatcher().dispatchBatch();

        JsonNode messages = json.readTree(new ApiClient(mvc, json).get("/api/test/mailbox?to=" + owner.email())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode digest = null;
        for (JsonNode m : messages) {
            if ("COMPLIANCE_DIGEST".equals(m.get("kind").asString())) {
                digest = m;
            }
        }
        assertThat(digest).isNotNull();
        assertThat(digest.get("subject").asString()).isEqualTo("VendorFlow: 1 document expiring soon");
        assertThat(digest.get("text").asString()).contains("Mailbox Plumbing");
        assertThat(digest.get("links").get(0).asString()).isEqualTo("http://localhost:3000/dashboard");

        // a second call the same day finds nothing new
        owner.client().remoteAddr("127.0.0.1").post("/api/test/reminders/run", Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.newLedgerRows").value(0)).andExpect(jsonPath("$.digestsEnqueued").value(0));
    }

    @Test
    void documentRequestsReachTheMailboxWithReplyToAndTextBody() throws Exception {
        TestAccounts accounts = new TestAccounts(mvc, json, jdbc);
        Account owner = accounts.signup(TestAccounts.uniqueEmail(), TestAccounts.PASSWORD, "Dora Owner",
                "E2E Req Org " + UUID.randomUUID());
        String vendorEmail = "vendor-" + UUID.randomUUID().toString().substring(0, 8) + "@vendor.example";
        String vendorId = json.readTree(owner.client().remoteAddr("127.0.0.1").post("/api/v1/vendors",
                Map.of("companyName", "Req Vendor", "email", vendorEmail)).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString()).get("id").asString();
        String w9 = null;
        for (JsonNode t : json.readTree(owner.client().get("/api/v1/document-types").andReturn().getResponse()
                .getContentAsString())) {
            if (t.get("code").asString().equals("W9")) {
                w9 = t.get("id").asString();
            }
        }
        owner.client().post("/api/v1/vendors/" + vendorId + "/document-requests", Map.of("documentTypeId", w9))
                .andExpect(status().isAccepted());
        jdbc.update("delete from notification where recipient_email <> ?", vendorEmail);
        mailboxDispatcher().dispatchBatch();

        JsonNode messages = json.readTree(new ApiClient(mvc, json).get("/api/test/mailbox?to=" + vendorEmail)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).get("kind").asString()).isEqualTo("DOCUMENT_REQUEST");
        assertThat(messages.get(0).get("replyTo").asString()).isEqualTo(owner.email());
        assertThat(messages.get(0).get("subject").asString()).endsWith(" requests your W-9");
        assertThat(messages.get(0).get("text").asString()).contains("W-9");
    }

    @Test
    void runEndpointIsGuarded() throws Exception {
        TestAccounts accounts = new TestAccounts(mvc, json, jdbc);
        Account owner = accounts.signup(TestAccounts.uniqueEmail(), TestAccounts.PASSWORD, "Dora Owner",
                "E2E Guard Org " + UUID.randomUUID());
        Account viewer = accounts.memberOf(owner.organizationId(), "VIEWER", "Vic Viewer");
        viewer.client().remoteAddr("127.0.0.1").post("/api/test/reminders/run", Map.of()).andExpect(status().isForbidden());
        new ApiClient(mvc, json).primeCsrf().post("/api/test/reminders/run", Map.of())
                .andExpect(status().isUnauthorized());
        Account remote = accounts.signup(TestAccounts.uniqueEmail(), TestAccounts.PASSWORD, "Rita Remote",
                "E2E Remote Org " + UUID.randomUUID());
        remote.client().remoteAddr("203.0.113.9").post("/api/test/reminders/run", Map.of())
                .andExpect(status().isNotFound());
    }
}
