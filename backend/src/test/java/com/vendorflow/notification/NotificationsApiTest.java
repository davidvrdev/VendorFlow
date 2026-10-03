package com.vendorflow.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.support.ApiClient;
import com.vendorflow.support.IntegrationTest;
import com.vendorflow.support.TestAccounts;
import com.vendorflow.support.TestAccounts.Account;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** GET /notifications: OWNER/ADMIN, org-scoped, newest first, no account emails, no payloads. */
class NotificationsApiTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxService outbox;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired PlatformTransactionManager txManager;

    TestAccounts accounts;
    Account owner;
    Account admin;
    Account member;
    Account viewer;
    Account other;
    String orgId;

    @BeforeEach
    void setUp() throws Exception {
        accounts = new TestAccounts(mvc, json, jdbc);
        owner = accounts.signup(TestAccounts.uniqueEmail(), TestAccounts.PASSWORD, "Dora Owner",
                "Activity Org " + UUID.randomUUID());
        orgId = owner.organizationId();
        admin = accounts.memberOf(orgId, "ADMIN", "Adam Admin");
        member = accounts.memberOf(orgId, "MEMBER", "Mia Member");
        viewer = accounts.memberOf(orgId, "VIEWER", "Vic Viewer");
        other = accounts.signup(TestAccounts.uniqueEmail(), TestAccounts.PASSWORD, "Olga Other",
                "Other Org " + UUID.randomUUID());
    }

    void enqueue(String org, NotificationKind kind, String to, Map<String, Object> payload) {
        new TransactionTemplate(txManager).executeWithoutResult(s -> outbox.enqueue(kind,
                org == null ? null : UUID.fromString(org), to, "k-" + UUID.randomUUID(), payload));
    }

    JsonNode list(Account who, String query) throws Exception {
        return json.readTree(who.client().get("/api/v1/notifications" + query).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
    }

    @Test
    void ownerAndAdminSeeOrganizationActivityNewestFirstWithoutPayloads() throws Exception {
        enqueue(orgId, NotificationKind.DOCUMENT_REQUEST, "first@vendor.example",
                Map.of("organizationName", "Org", "documentTypeName", "W-9", "secretish", "do-not-leak"));
        clock.advance(Duration.ofMinutes(1));
        enqueue(orgId, NotificationKind.COMPLIANCE_DIGEST, "second@owner.example",
                Map.of("organizationName", "Org", "expired", List.of(), "expiring", List.of()));

        for (Account who : List.of(owner, admin)) {
            JsonNode page = list(who, "");
            assertThat(page.get("page").asInt()).isZero();
            assertThat(page.get("size").asInt()).isEqualTo(25);
            List<String> recipients = new ArrayList<>();
            page.get("items").forEach(i -> recipients.add(i.get("recipientEmail").asString()));
            assertThat(recipients).startsWith("second@owner.example", "first@vendor.example");
            JsonNode first = page.get("items").get(1);
            assertThat(first.get("kind").asString()).isEqualTo("DOCUMENT_REQUEST");
            assertThat(first.get("status").asString()).isEqualTo("PENDING");
            assertThat(first.get("attempts").asInt()).isZero();
            assertThat(first.get("lastError").isNull()).isTrue();
            assertThat(first.get("sentAt").isNull()).isTrue();
            assertThat(first.get("id").asString()).isNotBlank();
            assertThat(first.get("createdAt").asString()).isNotBlank();
            assertThat(first.has("payload")).isFalse();
            assertThat(first.toString()).doesNotContain("do-not-leak");
        }
    }

    @Test
    void failedAndSentStatesAreVisible() throws Exception {
        jdbc.update("delete from notification");
        enqueue(orgId, NotificationKind.DOCUMENT_REQUEST, "a@vendor.example",
                Map.of("organizationName", "Org", "documentTypeName", "W-9", "vendorName", "V"));
        emailSender.failWith(new EmailDeliveryException("Resend HTTP 429", false));
        dispatcher.dispatchBatch();
        JsonNode failed = list(owner, "").get("items").get(0);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("attempts").asInt()).isEqualTo(1);
        assertThat(failed.get("lastError").asString()).contains("429");

        emailSender.reset();
        clock.advance(Duration.ofMinutes(2));
        dispatcher.dispatchBatch();
        JsonNode sent = list(owner, "").get("items").get(0);
        assertThat(sent.get("status").asString()).isEqualTo("SENT");
        assertThat(sent.get("sentAt").isNull()).isFalse();
    }

    @Test
    void memberViewerAndAnonymousAreDenied() throws Exception {
        member.client().get("/api/v1/notifications").andExpect(status().isForbidden());
        viewer.client().get("/api/v1/notifications").andExpect(status().isForbidden());
        new ApiClient(mvc, json).get("/api/v1/notifications").andExpect(status().isUnauthorized());
    }

    @Test
    void otherOrganizationsAndAccountEmailsNeverAppear() throws Exception {
        enqueue(orgId, NotificationKind.DOCUMENT_REQUEST, "mine@vendor.example", Map.of("organizationName", "Org"));
        enqueue(other.organizationId(), NotificationKind.DOCUMENT_REQUEST, "theirs@vendor.example",
                Map.of("organizationName", "Other"));
        // account emails: no organization (signup already created verification emails for every account above)
        enqueue(null, NotificationKind.PASSWORD_RESET, "reset@account.example", Map.of("token", "t"));
        // ... and even if one were wrongly tagged with the organization, the kind filter hides it
        enqueue(orgId, NotificationKind.EMAIL_VERIFICATION, "tagged@account.example", Map.of("token", "t"));

        List<String> recipients = new ArrayList<>();
        list(owner, "?size=100").get("items").forEach(i -> recipients.add(i.get("recipientEmail").asString()));
        assertThat(recipients).contains("mine@vendor.example")
                .doesNotContain("theirs@vendor.example", "reset@account.example", "tagged@account.example",
                        owner.email());
        List<String> theirs = new ArrayList<>();
        list(other, "?size=100").get("items").forEach(i -> theirs.add(i.get("recipientEmail").asString()));
        assertThat(theirs).contains("theirs@vendor.example").doesNotContain("mine@vendor.example");
    }

    @Test
    void pagingIsClamped() throws Exception {
        for (int i = 0; i < 3; i++) {
            enqueue(orgId, NotificationKind.DOCUMENT_REQUEST, "p" + i + "@vendor.example", Map.of("organizationName", "O"));
        }
        JsonNode page = list(owner, "?size=2&page=1");
        assertThat(page.get("items")).hasSize(1);
        assertThat(page.get("totalItems").asInt()).isEqualTo(3);
        assertThat(list(owner, "?size=1000").get("size").asInt()).isEqualTo(100);
        assertThat(list(owner, "?page=-2").get("page").asInt()).isZero();
    }
}
