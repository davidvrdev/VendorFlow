package com.vendorflow.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.support.ApiClient;
import com.vendorflow.support.IntegrationTest;
import com.vendorflow.support.TestAccounts;
import com.vendorflow.support.TestAccounts.Account;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OrganizationApiTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;

    TestAccounts accounts;

    @BeforeEach
    void setUp() {
        accounts = new TestAccounts(mvc, json, jdbc);
    }

    @Test
    void getReturnsActiveOrganizationWithDefaults() throws Exception {
        Account owner = accounts.signup("Acme Properties");
        owner.client().get("/api/v1/organization")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(owner.organizationId()))
                .andExpect(jsonPath("$.name").value("Acme Properties"))
                .andExpect(jsonPath("$.timeZone").value("America/New_York"))
                .andExpect(jsonPath("$.expiringWindowDays").value(30))
                .andExpect(jsonPath("$.reminderOffsetsDays[0]").value(30))
                .andExpect(jsonPath("$.reminderOffsetsDays.length()").value(4))
                .andExpect(jsonPath("$.remindersEnabled").value(true))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void anonymousGets401() throws Exception {
        new ApiClient(mvc, json).get("/api/v1/organization").andExpect(status().isUnauthorized());
    }

    @Test
    void ownerCanPatchAndAuditRecordsBeforeAndAfter() throws Exception {
        Account owner = accounts.signup("Old Name");
        owner.client().patch("/api/v1/organization", Map.of(
                        "name", "New Name", "timeZone", "America/Chicago", "expiringWindowDays", 45,
                        "reminderOffsetsDays", List.of(60, 30, 7), "remindersEnabled", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New Name"))
                .andExpect(jsonPath("$.timeZone").value("America/Chicago"))
                .andExpect(jsonPath("$.expiringWindowDays").value(45))
                .andExpect(jsonPath("$.reminderOffsetsDays.length()").value(3))
                .andExpect(jsonPath("$.remindersEnabled").value(false));

        String metadata = jdbc.queryForObject("""
                select metadata::text from audit_event
                where organization_id = ?::uuid and action = 'organization.updated'""", String.class,
                owner.organizationId());
        JsonNode changes = json.readTree(metadata).get("changes");
        assertThat(changes.get("name").get("before").asString()).isEqualTo("Old Name");
        assertThat(changes.get("name").get("after").asString()).isEqualTo("New Name");
        assertThat(changes.get("timeZone").get("before").asString()).isEqualTo("America/New_York");
        assertThat(changes.get("expiringWindowDays").get("after").asInt()).isEqualTo(45);
        assertThat(changes.get("reminderOffsetsDays").get("before").size()).isEqualTo(4);
        assertThat(changes.get("remindersEnabled").get("after").asBoolean()).isFalse();
        String actor = jdbc.queryForObject("""
                select actor_user_id::text from audit_event
                where organization_id = ?::uuid and action = 'organization.updated'""", String.class,
                owner.organizationId());
        assertThat(actor).isEqualTo(owner.userId());
        // request id and a hashed (never raw) IP are recorded
        Map<String, Object> row = jdbc.queryForMap("""
                select request_id, ip_hash from audit_event
                where organization_id = ?::uuid and action = 'organization.updated'""", owner.organizationId());
        assertThat(row.get("request_id")).isNotNull();
        assertThat((String) row.get("ip_hash")).matches("[0-9a-f]{64}").doesNotContain("127.0.0.1");
    }

    @Test
    void organizationCreatedIsAudited() throws Exception {
        Account owner = accounts.signup("Audited Org");
        Integer count = jdbc.queryForObject("""
                select count(*) from audit_event where organization_id = ?::uuid and action = 'organization.created'""",
                Integer.class, owner.organizationId());
        assertThat(count).isEqualTo(1);
    }

    @Test
    void partialPatchLeavesOtherFieldsAndNoopWritesNoAudit() throws Exception {
        Account owner = accounts.signup("Partial Org");
        owner.client().patch("/api/v1/organization", Map.of("remindersEnabled", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Partial Org"))
                .andExpect(jsonPath("$.expiringWindowDays").value(30));
        owner.client().patch("/api/v1/organization", Map.of("remindersEnabled", false)).andExpect(status().isOk());
        Integer audits = jdbc.queryForObject("""
                select count(*) from audit_event where organization_id = ?::uuid and action = 'organization.updated'""",
                Integer.class, owner.organizationId());
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void adminCanPatchButMemberAndViewerCannot() throws Exception {
        Account owner = accounts.signup("Roles Org");
        Account admin = accounts.signup("Admin Home");
        Account member = accounts.signup("Member Home");
        Account viewer = accounts.signup("Viewer Home");
        accounts.joinOrganization(admin, owner.organizationId(), "ADMIN");
        accounts.joinOrganization(member, owner.organizationId(), "MEMBER");
        accounts.joinOrganization(viewer, owner.organizationId(), "VIEWER");

        admin.client().patch("/api/v1/organization", Map.of("name", "Renamed By Admin")).andExpect(status().isOk());
        member.client().patch("/api/v1/organization", Map.of("name", "Nope"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://vendorflow.app/problems/forbidden"));
        viewer.client().patch("/api/v1/organization", Map.of("name", "Nope"))
                .andExpect(status().isForbidden());

        // everyone can read
        viewer.client().get("/api/v1/organization").andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed By Admin"));
        assertThat(jdbc.queryForObject("select name from organization where id = ?::uuid", String.class,
                owner.organizationId())).isEqualTo("Renamed By Admin");
    }

    @Test
    void roleChangeAppliesImmediatelyWithoutNewLogin() throws Exception {
        Account owner = accounts.signup("Demote Org");
        Account admin = accounts.signup("Other Home");
        accounts.joinOrganization(admin, owner.organizationId(), "ADMIN");
        admin.client().patch("/api/v1/organization", Map.of("name", "As Admin")).andExpect(status().isOk());

        jdbc.update("update membership set role = 'VIEWER' where user_id = ?::uuid and organization_id = ?::uuid",
                admin.userId(), owner.organizationId());

        admin.client().patch("/api/v1/organization", Map.of("name", "As Viewer")).andExpect(status().isForbidden());
    }

    @Test
    void invalidInputsAreRejectedWith400() throws Exception {
        Account owner = accounts.signup("Validation Org");
        ApiClient c = owner.client();
        c.patch("/api/v1/organization", Map.of("timeZone", "Mars/Olympus_Mons"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("timeZone"));
        c.patch("/api/v1/organization", Map.of("expiringWindowDays", 0)).andExpect(status().isBadRequest());
        c.patch("/api/v1/organization", Map.of("expiringWindowDays", 181)).andExpect(status().isBadRequest());
        c.patch("/api/v1/organization", Map.of("reminderOffsetsDays", List.of())).andExpect(status().isBadRequest());
        c.patch("/api/v1/organization", Map.of("reminderOffsetsDays", List.of(1, 2, 3, 4, 5, 6)))
                .andExpect(status().isBadRequest());
        c.patch("/api/v1/organization", Map.of("reminderOffsetsDays", List.of(7, 7)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reminderOffsetsDays"));
        c.patch("/api/v1/organization", Map.of("reminderOffsetsDays", List.of(0, 5))).andExpect(status().isBadRequest());
        c.patch("/api/v1/organization", Map.of("reminderOffsetsDays", List.of(5, 181))).andExpect(status().isBadRequest());
        c.patch("/api/v1/organization", Map.of("name", "   ")).andExpect(status().isBadRequest());
        c.patch("/api/v1/organization", Map.of("name", "x".repeat(121))).andExpect(status().isBadRequest());
        // unchanged
        c.get("/api/v1/organization").andExpect(jsonPath("$.name").value("Validation Org"))
                .andExpect(jsonPath("$.expiringWindowDays").value(30));
    }

    @Test
    void tenantIsolationPatchOnlyChangesCallersOrganization() throws Exception {
        Account a = accounts.signup("Org A");
        Account b = accounts.signup("Org B");

        // B tries to smuggle A's id into the body and the query string: both are ignored.
        b.client().perform(org.springframework.http.HttpMethod.PATCH,
                        "/api/v1/organization?organizationId=" + a.organizationId(),
                        Map.of("name", "B renamed", "organizationId", a.organizationId(), "id", a.organizationId()),
                        true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(b.organizationId()));

        assertThat(jdbc.queryForObject("select name from organization where id = ?::uuid", String.class,
                a.organizationId())).isEqualTo("Org A");
        assertThat(jdbc.queryForObject("select name from organization where id = ?::uuid", String.class,
                b.organizationId())).isEqualTo("B renamed");
        a.client().get("/api/v1/organization").andExpect(jsonPath("$.name").value("Org A"));
    }

    @Test
    void userWithNoActiveOrganizationGets403OnTenantEndpoint() throws Exception {
        Account owner = accounts.signup("Soon Gone");
        jdbc.update("delete from membership where user_id = ?::uuid", owner.userId());
        owner.client().patch("/api/v1/organization", Map.of("name", "x"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("No active organization"));
    }
}
