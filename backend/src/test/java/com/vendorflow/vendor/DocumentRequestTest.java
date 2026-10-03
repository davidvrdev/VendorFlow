package com.vendorflow.vendor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.notification.EmailMessage;
import com.vendorflow.notification.OutboxDispatcher;
import com.vendorflow.support.IntegrationTest;
import com.vendorflow.support.TestAccounts;
import com.vendorflow.support.TestAccounts.Account;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** POST /vendors/{id}/document-requests: rules, idempotency per org-local day, roles, tenant isolation, audit. */
class DocumentRequestTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxDispatcher dispatcher;

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
                "Requests Org " + UUID.randomUUID());
        orgId = owner.organizationId();
        admin = accounts.memberOf(orgId, "ADMIN", "Adam Admin");
        member = accounts.memberOf(orgId, "MEMBER", "Mia Member");
        viewer = accounts.memberOf(orgId, "VIEWER", "Vic Viewer");
        other = accounts.signup(TestAccounts.uniqueEmail(), TestAccounts.PASSWORD, "Olga Other",
                "Other Org " + UUID.randomUUID());
    }

    String vendor(Account who, String name, String email) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("companyName", name);
        body.put("contactName", "Carl Contact");
        if (email != null) {
            body.put("email", email);
        }
        return json.readTree(who.client().post("/api/v1/vendors", body).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString()).get("id").asString();
    }

    JsonNode type(Account who, String code) throws Exception {
        JsonNode types = json.readTree(who.client().get("/api/v1/document-types").andReturn().getResponse()
                .getContentAsString());
        for (JsonNode t : types) {
            if (t.get("code").asString().equals(code)) {
                return t;
            }
        }
        throw new AssertionError("no type " + code);
    }

    static String path(String vendorId) {
        return "/api/v1/vendors/" + vendorId + "/document-requests";
    }

    Map<String, Object> req(JsonNode type) {
        return Map.of("documentTypeId", type.get("id").asString());
    }

    int requestRows(String vendorEmail) {
        return jdbc.queryForObject("select count(*) from notification where kind = 'DOCUMENT_REQUEST' "
                + "and recipient_email = ? and organization_id = ?::uuid", Integer.class, vendorEmail, orgId);
    }

    @Test
    void happyPathEnqueuesAnEmailWithReplyToAuditsAndShowsInHistory() throws Exception {
        jdbc.update("delete from notification");
        String email = "contact-" + UUID.randomUUID().toString().substring(0, 8) + "@vendor.example";
        String vendorId = vendor(owner, "Acme <b>Plumbing</b>", email);
        JsonNode w9 = type(owner, "W9");

        owner.client().post(path(vendorId), req(w9)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.recipientEmail").value(email)).andExpect(jsonPath("$.requestedAt").isNotEmpty());

        assertThat(requestRows(email)).isEqualTo(1);
        dispatcher.dispatchBatch();
        EmailMessage sent = emailSender.sent().stream().filter(m -> m.to().equals(email)).findFirst().orElseThrow();
        String orgName = jdbc.queryForObject("select name from organization where id = ?::uuid", String.class, orgId);
        assertThat(sent.subject()).isEqualTo(orgName + " requests your " + w9.get("name").asString());
        assertThat(sent.replyTo()).isEqualTo(owner.email());
        assertThat(sent.htmlBody()).contains("&lt;b&gt;Plumbing&lt;/b&gt;").doesNotContain("<b>Plumbing</b>");
        assertThat(sent.textBody()).contains(w9.get("name").asString());

        JsonNode history = json.readTree(owner.client().get("/api/v1/vendors/" + vendorId + "/history")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode event = null;
        for (JsonNode e : history.get("items")) {
            if (e.get("action").asString().equals("vendor.document_requested")) {
                event = e;
            }
        }
        assertThat(event).isNotNull();
        assertThat(event.get("actor").get("fullName").asString()).isEqualTo("Dora Owner");
        assertThat(event.get("changes").get("typeCode").get("after").asString()).isEqualTo("W9");
        assertThat(event.get("changes").get("typeName").get("after").asString()).isEqualTo(w9.get("name").asString());
        assertThat(event.get("changes").get("recipientEmail").get("after").asString()).isEqualTo(email);
        assertThat(event.get("detail").asString()).isEqualTo(w9.get("name").asString() + " - " + email);
    }

    @Test
    void aRepeatTheSameOrgLocalDayIs409ButAnotherTypeOrDayIsFine() throws Exception {
        String email = "c-" + UUID.randomUUID().toString().substring(0, 8) + "@vendor.example";
        String vendorId = vendor(owner, "Acme", email);
        JsonNode coi = type(owner, "COI");
        owner.client().post(path(vendorId), req(coi)).andExpect(status().isAccepted());
        owner.client().post(path(vendorId), req(coi)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Already requested today"));
        // a teammate repeating it is also a repeat (the key is per vendor, type and day)
        admin.client().post(path(vendorId), req(coi)).andExpect(status().isConflict());
        assertThat(requestRows(email)).isEqualTo(1);

        owner.client().post(path(vendorId), req(type(owner, "W9"))).andExpect(status().isAccepted());
        clock.advance(Duration.ofDays(1));
        owner.client().post(path(vendorId), req(coi)).andExpect(status().isAccepted());
        assertThat(requestRows(email)).isEqualTo(3);
    }

    @Test
    void vendorWithoutEmailOrInactiveIs422() throws Exception {
        String noEmail = vendor(owner, "No Email Co", null);
        owner.client().post(path(noEmail), req(type(owner, "W9"))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Vendor has no email"));

        String inactive = vendor(owner, "Inactive Co", "x@vendor.example");
        owner.client().post("/api/v1/vendors/" + inactive + "/deactivate", Map.of()).andExpect(status().isOk());
        owner.client().post(path(inactive), req(type(owner, "W9"))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Vendor is inactive"));
    }

    @Test
    void documentTypeMustBeAnActiveTypeOfTheOrganization() throws Exception {
        String vendorId = vendor(owner, "Acme", "a@vendor.example");
        owner.client().post(path(vendorId), Map.of("documentTypeId", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("documentTypeId"));
        // a type of ANOTHER organization is as unknown as a random one
        owner.client().post(path(vendorId), req(type(other, "W9"))).andExpect(status().isBadRequest());
        owner.client().post(path(vendorId), Map.of()).andExpect(status().isBadRequest());
        // deactivated type
        JsonNode other1 = type(owner, "OTHER");
        jdbc.update("update document_type set active = false where id = ?::uuid", other1.get("id").asString());
        owner.client().post(path(vendorId), req(other1)).andExpect(status().isBadRequest());
        assertThat(requestRows("a@vendor.example")).isZero();
    }

    @Test
    void rolesAndTenantIsolation() throws Exception {
        String vendorId = vendor(owner, "Acme", "roles@vendor.example");
        JsonNode w9 = type(owner, "W9");
        viewer.client().post(path(vendorId), req(w9)).andExpect(status().isForbidden());
        assertThat(requestRows("roles@vendor.example")).isZero();
        member.client().post(path(vendorId), req(w9)).andExpect(status().isAccepted());
        admin.client().post(path(vendorId), req(type(owner, "COI"))).andExpect(status().isAccepted());
        owner.client().post(path(vendorId), req(type(owner, "CONTRACT"))).andExpect(status().isAccepted());

        // organization B cannot see org A's vendor, whatever type it sends
        other.client().post(path(vendorId), req(type(other, "W9"))).andExpect(status().isNotFound());
        owner.client().post(path(UUID.randomUUID().toString()), req(w9)).andExpect(status().isNotFound());
    }

    @Test
    void anonymousIsRejected() throws Exception {
        String vendorId = vendor(owner, "Acme", "anon@vendor.example");
        new com.vendorflow.support.ApiClient(mvc, json).primeCsrf().post(path(vendorId), req(type(owner, "W9")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void perUserRateLimitIs30PerMinute() throws Exception {
        List<JsonNode> types = new java.util.ArrayList<>();
        for (JsonNode t : json.readTree(owner.client().get("/api/v1/document-types").andReturn().getResponse()
                .getContentAsString())) {
            types.add(t);
        }
        assertThat(types).hasSize(8);
        int accepted = 0;
        int limited = 0;
        for (int v = 0; v < 4; v++) {
            String vendorId = vendor(owner, "Rate Vendor " + v, "rate" + v + "@vendor.example");
            for (JsonNode t : types) {
                int code = owner.client().post(path(vendorId), req(t)).andReturn().getResponse().getStatus();
                if (code == 202) {
                    accepted++;
                } else {
                    assertThat(code).isEqualTo(429);
                    limited++;
                }
            }
        }
        assertThat(accepted).isEqualTo(30);
        assertThat(limited).isEqualTo(2);
        // another user has their own budget
        String vendorId = vendor(admin, "Rate Vendor Admin", "rateadmin@vendor.example");
        admin.client().post(path(vendorId), req(types.get(0))).andExpect(status().isAccepted());
    }
}
