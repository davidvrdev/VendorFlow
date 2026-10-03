package com.vendorflow.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.document.application.DocumentTypeService;
import com.vendorflow.organization.domain.OrganizationCreatedEvent;
import com.vendorflow.support.IntegrationTest;
import com.vendorflow.support.TestAccounts;
import com.vendorflow.support.TestAccounts.Account;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class DocumentTypeTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationEventPublisher events;
    @Autowired TransactionTemplate tx;
    @Autowired DocumentTypeService service;
    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager em;

    @Test
    void signupSeedsTheEightDefaultTypesInOrder() throws Exception {
        var accounts = new TestAccounts(mvc, json, jdbc);
        Account owner = accounts.signup("Seeded " + UUID.randomUUID());

        JsonNode types = json.readTree(owner.client().get("/api/v1/document-types").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8)).andReturn().getResponse().getContentAsString());
        List<String> codes = new ArrayList<>();
        types.forEach(t -> codes.add(t.get("code").asString()));
        assertThat(codes).containsExactly("COI", "GENERAL_LIABILITY", "WORKERS_COMP", "BUSINESS_LICENSE",
                "PROFESSIONAL_LICENSE", "W9", "CONTRACT", "OTHER");

        List<String> required = new ArrayList<>();
        types.forEach(t -> {
            if (t.get("requiredByDefault").asBoolean()) {
                required.add(t.get("code").asString());
            }
        });
        assertThat(required).containsExactly("COI", "WORKERS_COMP", "W9");
        owner.client().get("/api/v1/document-types").andExpect(jsonPath("$[0].name").value("Certificate of Insurance"))
                .andExpect(jsonPath("$[0].hasExpiration").value(true))
                .andExpect(jsonPath("$[2].name").value("Workers' Compensation"))
                .andExpect(jsonPath("$[5].hasExpiration").value(false))
                .andExpect(jsonPath("$[7].sortOrder").value(80));
    }

    @Test
    void aNewOrganizationsFirstVendorGetsCoiWorkersCompAndW9() throws Exception {
        Account owner = new TestAccounts(mvc, json, jdbc).signup("First Vendor Org " + UUID.randomUUID());
        owner.client().post("/api/v1/vendors", java.util.Map.of("companyName", "First")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.requirements.length()").value(3))
                .andExpect(jsonPath("$.requirements[0].code").value("COI"))
                .andExpect(jsonPath("$.requirements[1].code").value("WORKERS_COMP"))
                .andExpect(jsonPath("$.requirements[2].code").value("W9"));
    }

    @Test
    void eachOrganizationOnlySeesItsOwnTypes() throws Exception {
        var accounts = new TestAccounts(mvc, json, jdbc);
        Account a = accounts.signup("Types A " + UUID.randomUUID());
        Account b = accounts.signup("Types B " + UUID.randomUUID());
        JsonNode ta = json.readTree(a.client().get("/api/v1/document-types").andReturn().getResponse()
                .getContentAsString());
        JsonNode tb = json.readTree(b.client().get("/api/v1/document-types").andReturn().getResponse()
                .getContentAsString());
        for (JsonNode x : ta) {
            for (JsonNode y : tb) {
                assertThat(x.get("id").asString()).isNotEqualTo(y.get("id").asString());
            }
        }
    }

    @Test
    void inactiveTypesAreNotListed() throws Exception {
        Account owner = new TestAccounts(mvc, json, jdbc).signup("Inactive Types " + UUID.randomUUID());
        jdbc.update("update document_type set active = false where organization_id = ?::uuid and code = 'OTHER'",
                owner.organizationId());
        owner.client().get("/api/v1/document-types").andExpect(jsonPath("$.length()").value(7));
    }

    /**
     * The seeding listener must run in the publisher's transaction: when that transaction rolls back, the seeded rows
     * go with it (an AFTER_COMMIT or REQUIRES_NEW listener would leave them behind or fail on the missing organization).
     */
    @Test
    void seedingRunsInsideThePublishersTransaction() {
        UUID orgId = UUID.randomUUID();
        int[] seenInside = new int[1];
        tx.executeWithoutResult(status -> {
            jdbc.update("insert into organization (id, name, created_at, updated_at) values (?, 'Rolled back', now(), now())",
                    orgId);
            events.publishEvent(new OrganizationCreatedEvent(orgId));
            em.flush(); // seeded rows are only in the persistence context until flushed
            seenInside[0] = jdbc.queryForObject("select count(*) from document_type where organization_id = ?",
                    Integer.class, orgId);
            status.setRollbackOnly();
        });
        assertThat(seenInside[0]).isEqualTo(8);
        assertThat(jdbc.queryForObject("select count(*) from organization where id = ?", Integer.class, orgId))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from document_type where organization_id = ?",
                Integer.class, orgId)).isZero();
    }

    @Test
    void aSeedingFailureFailsTheWholeTransaction() {
        UUID missingOrg = UUID.randomUUID(); // no such organization: the FK makes seeding fail
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> tx.executeWithoutResult(status -> events.publishEvent(new OrganizationCreatedEvent(missingOrg))));
        assertThat(jdbc.queryForObject("select count(*) from document_type where organization_id = ?",
                Integer.class, missingOrg)).isZero();
    }

    @Test
    void seedDefaultsRequiresATransactionAndIsIdempotent() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalTransactionStateException.class,
                () -> service.seedDefaults(UUID.randomUUID()));

        UUID orgId = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            jdbc.update("insert into organization (id, name, created_at, updated_at) values (?, 'Idempotent', now(), now())",
                    orgId);
            service.seedDefaults(orgId);
            service.seedDefaults(orgId);
            assertThat(jdbc.queryForObject("select count(*) from document_type where organization_id = ?",
                    Integer.class, orgId)).isEqualTo(8);
            status.setRollbackOnly();
        });
    }

    @Test
    void findActiveByIdsNeverReturnsAForeignOrganizationsTypes() throws Exception {
        var accounts = new TestAccounts(mvc, json, jdbc);
        Account a = accounts.signup("Find A " + UUID.randomUUID());
        Account b = accounts.signup("Find B " + UUID.randomUUID());
        List<UUID> idsOfA = jdbc.queryForList("select id from document_type where organization_id = ?::uuid",
                UUID.class, a.organizationId());
        assertThat(service.findActiveByIds(UUID.fromString(a.organizationId()), idsOfA)).hasSize(8);
        assertThat(service.findActiveByIds(UUID.fromString(b.organizationId()), idsOfA)).isEmpty();
    }

    @Test
    void anonymousCannotListTypes() throws Exception {
        new TestAccounts(mvc, json, jdbc).newClient().get("/api/v1/document-types")
                .andExpect(status().isUnauthorized());
    }
}
