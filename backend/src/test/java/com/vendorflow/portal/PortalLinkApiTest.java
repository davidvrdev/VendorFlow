package com.vendorflow.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.notification.EmailMessage;
import com.vendorflow.notification.NotificationKind;
import com.vendorflow.notification.OutboxDispatcher;
import com.vendorflow.support.EmailTokens;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Staff endpoints: create / list / revoke. Happy path, validation, lowest denied role, cross-tenant 404. */
class PortalLinkApiTest extends PortalTestBase {

    @Autowired OutboxDispatcher dispatcher;

    @Test
    void createReturnsTheLinkAndTheRawTokenOnlyOnceAndStoresOnlyItsHash() throws Exception {
        String vendor = createVendor(member, "Acme", "vendor@example.com");
        String coi = typeId(member, "COI");
        String w9 = typeId(member, "W9");

        JsonNode created = json.readTree(postLink(member, vendor, body(List.of(coi, w9)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.emailQueued").value(false))
                .andExpect(jsonPath("$.link.status").value("ACTIVE"))
                .andExpect(jsonPath("$.link.vendorId").value(vendor))
                .andExpect(jsonPath("$.link.useCount").value(0))
                .andExpect(jsonPath("$.link.maxUploads").value(20))
                .andExpect(jsonPath("$.link.createdBy.fullName").value("Mia Member"))
                .andExpect(jsonPath("$.link.documentTypes.length()").value(2))
                .andReturn().getResponse().getContentAsString());

        String token = tokenOf(created);
        assertThat(token).hasSize(43);
        assertThat(created.get("url").asString()).isEqualTo("http://localhost:3000/portal#token=" + token);
        // 14 days by default
        long days = Duration.between(java.time.Instant.parse(created.get("link").get("createdAt").asString()),
                java.time.Instant.parse(created.get("link").get("expiresAt").asString())).toDays();
        assertThat(days).isEqualTo(14);
        // Neither the token nor anything that could be replayed as it is in the link JSON.
        assertThat(created.get("link").toString()).doesNotContain(token);

        String id = created.get("link").get("id").asString();
        assertThat(jdbc.queryForObject("select token_hash from vendor_upload_link where id = ?::uuid", String.class,
                id)).hasSize(64).isNotEqualTo(token);
        assertThat(count("select count(*) from vendor_upload_link where token_hash = ?", token)).isZero();
        assertThat(count("select count(*) from vendor_upload_link_type where link_id = ?::uuid", id)).isEqualTo(2);
        // Audit: created, without the token or its hash.
        assertThat(jdbc.queryForObject("select metadata::text from audit_event where action = 'portal_link.created' "
                + "and entity_id = ?::uuid", String.class, id)).doesNotContain(token).contains("maxUploads");
    }

    @Test
    void explicitExpiryAndMaxUploadsAreHonoured() throws Exception {
        String vendor = createVendor(member, "Acme", null);
        JsonNode created = createLink(member, vendor, List.of(typeId(member, "W9")), "expiresInDays", 30,
                "maxUploads", 3);
        JsonNode link = created.get("link");
        assertThat(link.get("maxUploads").asInt()).isEqualTo(3);
        assertThat(Duration.between(java.time.Instant.parse(link.get("createdAt").asString()),
                java.time.Instant.parse(link.get("expiresAt").asString())).toDays()).isEqualTo(30);
    }

    @Test
    void sendEmailQueuesTheDocumentRequestWithThePortalLinkAndScrubsTheTokenAfterDelivery() throws Exception {
        String vendor = createVendor(member, "Acme", "vendor@example.com");
        JsonNode created = createLink(member, vendor, List.of(typeId(member, "COI"), typeId(member, "W9")),
                "sendEmail", true);
        assertThat(created.get("emailQueued").asBoolean()).isTrue();
        String token = tokenOf(created);

        EmailMessage mail = EmailTokens.latest(dispatcher, emailSender, "vendor@example.com",
                NotificationKind.DOCUMENT_REQUEST);
        assertThat(mail.textBody()).contains("http://localhost:3000/portal#token=" + token)
                .contains("Certificate of Insurance").contains("W-9").contains("Do not forward");
        assertThat(mail.htmlBody()).contains("#token=" + token);
        assertThat(mail.subject()).doesNotContain(token);
        // Once SENT the dispatcher removes the secret from the outbox row.
        assertThat(count("select count(*) from notification where recipient_email = 'vendor@example.com' "
                + "and payload -> 'token' is not null")).isZero();
    }

    @Test
    void sendEmailWithoutVendorEmailIs422AndNothingIsCreated() throws Exception {
        String vendor = createVendor(member, "Acme", null);
        postLink(member, vendor, body(List.of(typeId(member, "W9")), "sendEmail", true))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://vendorflow.app/problems/vendor-no-email"));
        assertThat(count("select count(*) from vendor_upload_link where vendor_id = ?::uuid", vendor)).isZero();
    }

    @Test
    void inactiveVendorIs422() throws Exception {
        String vendor = createVendor(member, "Acme", "v@example.com");
        admin.client().post("/api/v1/vendors/" + vendor + "/deactivate", null).andExpect(status().isOk());
        postLink(member, vendor, body(List.of(typeId(member, "W9"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://vendorflow.app/problems/vendor-inactive"));
    }

    @Test
    void validationRejectsBadBodies() throws Exception {
        String vendor = createVendor(member, "Acme", null);
        String w9 = typeId(member, "W9");
        postLink(member, vendor, body(List.of())).andExpect(status().isBadRequest());
        postLink(member, vendor, Map.of()).andExpect(status().isBadRequest());
        postLink(member, vendor, body(List.of(w9), "expiresInDays", 31)).andExpect(status().isBadRequest());
        postLink(member, vendor, body(List.of(w9), "expiresInDays", 0)).andExpect(status().isBadRequest());
        postLink(member, vendor, body(List.of(w9), "maxUploads", 101)).andExpect(status().isBadRequest());
        postLink(member, vendor, body(List.of(w9), "maxUploads", 0)).andExpect(status().isBadRequest());
        postLink(member, vendor, body(List.of("not-a-uuid"))).andExpect(status().isBadRequest());
        // mass assignment: unknown fields are ignored, never bound
        JsonNode created = createLink(member, vendor, List.of(w9), "tokenHash", "x".repeat(64), "organizationId",
                UUID.randomUUID().toString(), "useCount", 99);
        assertThat(created.get("link").get("useCount").asInt()).isZero();
        assertThat(count("select count(*) from vendor_upload_link where organization_id = ?::uuid", orgId))
                .isEqualTo(1);
    }

    @Test
    void typesMustBeActiveRequirementsOfThisVendorAndOfThisOrganization() throws Exception {
        String vendor = createVendor(member, "Acme", null);
        // GENERAL_LIABILITY is a real, active type but not a default requirement of the vendor
        postLink(member, vendor, body(List.of(typeId(member, "GENERAL_LIABILITY"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("documentTypeIds"));
        // a type of ANOTHER organization is indistinguishable from an unknown one
        postLink(member, vendor, body(List.of(typeId(other, "W9"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("documentTypeIds"));
        postLink(member, vendor, body(List.of(UUID.randomUUID().toString())))
                .andExpect(status().isBadRequest());
        assertThat(count("select count(*) from vendor_upload_link where vendor_id = ?::uuid", vendor)).isZero();
    }

    @Test
    void viewerIsTheLowestDeniedRoleForCreateAndRevokeButMayList() throws Exception {
        String vendor = createVendor(member, "Acme", null);
        String w9 = typeId(member, "W9");
        postLink(viewer, vendor, body(List.of(w9))).andExpect(status().isForbidden());
        JsonNode created = createLink(member, vendor, List.of(w9));
        String linkId = created.get("link").get("id").asString();

        viewer.client().post(linksPath(vendor) + "/" + linkId + "/revoke", null).andExpect(status().isForbidden());
        viewer.client().get(linksPath(vendor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
        // the viewer's rejected attempts changed nothing
        assertThat(count("select count(*) from vendor_upload_link where vendor_id = ?::uuid", vendor)).isEqualTo(1);
        assertThat(count("select count(*) from vendor_upload_link where id = ?::uuid and revoked_at is not null",
                linkId)).isZero();
        // unauthenticated: 401
        new com.vendorflow.support.ApiClient(mvc, json).primeCsrf().get(linksPath(vendor))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listIsPaginatedNewestFirstAndShowsStatus() throws Exception {
        String vendor = createVendor(member, "Acme", null);
        String w9 = typeId(member, "W9");
        String first = createLink(member, vendor, List.of(w9)).get("link").get("id").asString();
        String second = createLink(member, vendor, List.of(w9)).get("link").get("id").asString();
        String third = createLink(member, vendor, List.of(w9)).get("link").get("id").asString();
        member.client().post(linksPath(vendor) + "/" + second + "/revoke", null).andExpect(status().isOk());

        JsonNode page = json.readTree(viewer.client().get(linksPath(vendor) + "?page=0&size=2")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(page.get("totalItems").asInt()).isEqualTo(3);
        assertThat(page.get("totalPages").asInt()).isEqualTo(2);
        assertThat(page.get("items")).hasSize(2);
        assertThat(page.get("items").get(0).get("id").asString()).isEqualTo(third);
        assertThat(page.get("items").get(1).get("id").asString()).isEqualTo(second);
        assertThat(page.get("items").get(1).get("status").asString()).isEqualTo("REVOKED");
        assertThat(page.toString()).doesNotContain("tokenHash").doesNotContain("token_hash");

        JsonNode page2 = json.readTree(viewer.client().get(linksPath(vendor) + "?page=1&size=2").andReturn()
                .getResponse().getContentAsString());
        assertThat(page2.get("items").get(0).get("id").asString()).isEqualTo(first);
        // size is clamped to 100
        viewer.client().get(linksPath(vendor) + "?size=1000").andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void expiredLinkIsListedAsExpired() throws Exception {
        String vendor = createVendor(member, "Acme", null);
        createLink(member, vendor, List.of(typeId(member, "W9")), "expiresInDays", 1);
        clock.advance(Duration.ofDays(2));
        viewer.client().get(linksPath(vendor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("EXPIRED"));
    }

    @Test
    void revokeIsIdempotentAndAuditedOnce() throws Exception {
        String vendor = createVendor(member, "Acme", null);
        JsonNode created = createLink(member, vendor, List.of(typeId(member, "W9")));
        String linkId = created.get("link").get("id").asString();
        String path = linksPath(vendor) + "/" + linkId + "/revoke";

        member.client().post(path, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revokedAt").exists());
        member.client().post(path, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVOKED"));
        assertThat(count("select count(*) from audit_event where action = 'portal_link.revoked' "
                + "and entity_id = ?::uuid", linkId)).isEqualTo(1);
        // and the vendor's link is dead
        portalGet(tokenOf(created)).andExpect(status().isNotFound());
    }

    @Test
    void crossTenantEverythingIs404() throws Exception {
        String vendor = createVendor(member, "Acme", "v@example.com");
        JsonNode created = createLink(member, vendor, List.of(typeId(member, "W9")));
        String linkId = created.get("link").get("id").asString();
        String foreignVendor = createVendor(other, "Foreign", null);
        String foreignLink = createLink(other, foreignVendor, List.of(typeId(other, "W9"))).get("link").get("id")
                .asString();

        // another organization's owner (highest role) cannot touch this vendor's links
        postLink(other, vendor, body(List.of(typeId(other, "W9")))).andExpect(status().isNotFound());
        other.client().get(linksPath(vendor)).andExpect(status().isNotFound());
        other.client().post(linksPath(vendor) + "/" + linkId + "/revoke", null).andExpect(status().isNotFound());
        // own vendor + foreign link id, and foreign vendor + own link id
        other.client().post(linksPath(foreignVendor) + "/" + linkId + "/revoke", null)
                .andExpect(status().isNotFound());
        member.client().post(linksPath(vendor) + "/" + foreignLink + "/revoke", null)
                .andExpect(status().isNotFound());
        // right org, wrong vendor in the path
        String sibling = createVendor(member, "Sibling", null);
        member.client().post(linksPath(sibling) + "/" + linkId + "/revoke", null).andExpect(status().isNotFound());
        assertThat(count("select count(*) from vendor_upload_link where revoked_at is not null and organization_id in (?::uuid, ?::uuid)",
                orgId, other.organizationId())).isZero();
    }
}
