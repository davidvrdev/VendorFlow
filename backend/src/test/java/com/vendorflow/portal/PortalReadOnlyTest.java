package com.vendorflow.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.billing.BillingTestBase;
import com.vendorflow.support.ApiClient;
import com.vendorflow.support.TestAccounts.Account;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

/** 402 behaviour (billing enabled): a read-only organization keeps its vendors' links readable but accepts no uploads. */
class PortalReadOnlyTest extends BillingTestBase {

    static final byte[] PDF = "%PDF-1.4\n%%EOF\n".getBytes();

    String typeId(Account who, String code) throws Exception {
        for (JsonNode t : json.readTree(who.client().get("/api/v1/document-types").andReturn().getResponse()
                .getContentAsString())) {
            if (t.get("code").asString().equals(code)) {
                return t.get("id").asString();
            }
        }
        throw new AssertionError(code);
    }

    @Test
    void readOnlyOrganizationBlocksPortalUploadsWithAFriendly402ButStaffCanStillRevoke() throws Exception {
        Account owner = accounts.signup("Lapsing Org " + UUID.randomUUID());
        String vendor = json.readTree(owner.client().post("/api/v1/vendors", Map.of("companyName", "Acme " + UUID.randomUUID()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
        String w9 = typeId(owner, "W9");
        String linksPath = "/api/v1/vendors/" + vendor + "/upload-links";
        JsonNode created = json.readTree(owner.client().post(linksPath, Map.of("documentTypeIds", List.of(w9)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        Matcher m = Pattern.compile("#token=([A-Za-z0-9_-]+)$").matcher(created.get("url").asString());
        assertThat(m.find()).isTrue();
        String token = m.group(1);
        String linkId = created.get("link").get("id").asString();
        ApiClient vendorBrowser = new ApiClient(mvc, json).remoteAddr("198.51.100.77");

        // active (trial): uploads work
        vendorBrowser.header("X-Portal-Token", token).postMultipartWithoutCsrf("/api/v1/portal/link/documents",
                new MockMultipartFile("file", "a.pdf", "application/octet-stream", PDF),
                Map.of("documentTypeId", w9)).andExpect(status().isCreated());

        setStatus(owner.organizationId(), "CANCELED");

        // the vendor sees the page and a friendly 402 on upload; nothing is stored or consumed
        vendorBrowser.header("X-Portal-Token", token).perform(HttpMethod.GET, "/api/v1/portal/link", null, false).andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptingUploads").value(false));
        vendorBrowser.header("X-Portal-Token", token).postMultipartWithoutCsrf("/api/v1/portal/link/documents",
                new MockMultipartFile("file", "b.pdf", "application/octet-stream", PDF),
                Map.of("documentTypeId", w9)).andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.type").value("https://vendorflow.app/problems/portal-uploads-unavailable"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("not accepting uploads")));
        assertThat(jdbc.queryForObject("select count(*) from document where vendor_id = ?::uuid", Integer.class,
                vendor)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select use_count from vendor_upload_link where id = ?::uuid", Integer.class,
                linkId)).isEqualTo(1);

        // staff: creating a link is a write (402 by the central guard), listing is a read, revoking stays possible
        owner.client().post(linksPath, Map.of("documentTypeIds", List.of(w9))).andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.type").value("https://vendorflow.app/problems/subscription-inactive"));
        owner.client().get(linksPath).andExpect(status().isOk());
        owner.client().post(linksPath + "/" + linkId + "/revoke", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
    }

    @Test
    void aReadOnlyStaffSessionDoesNotLeakItsOrganizationIntoSomeoneElsesPortalUpload() throws Exception {
        // The 402 interceptor reads the TenantContext of the SESSION; the portal must not be affected by the browser's
        // session (a lapsed org's staff member opening ANOTHER organization's vendor link).
        Account lapsed = accounts.signup("Lapsed Org " + UUID.randomUUID());
        setStatus(lapsed.organizationId(), "CANCELED");
        Account active = accounts.signup("Active Org " + UUID.randomUUID());
        String vendor = json.readTree(active.client().post("/api/v1/vendors", Map.of("companyName", "V " + UUID.randomUUID()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
        String w9 = typeId(active, "W9");
        JsonNode created = json.readTree(active.client().post("/api/v1/vendors/" + vendor + "/upload-links",
                Map.of("documentTypeIds", List.of(w9))).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString());
        Matcher m = Pattern.compile("#token=([A-Za-z0-9_-]+)$").matcher(created.get("url").asString());
        assertThat(m.find()).isTrue();

        lapsed.client().header("X-Portal-Token", m.group(1)).postMultipartWithoutCsrf("/api/v1/portal/link/documents",
                new MockMultipartFile("file", "a.pdf", "application/octet-stream", PDF),
                Map.of("documentTypeId", w9)).andExpect(status().isCreated());
    }
}
