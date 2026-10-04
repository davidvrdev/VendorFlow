package com.vendorflow.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.notification.EmailTemplates;
import com.vendorflow.notification.NotificationKind;
import com.vendorflow.notification.RenderedEmail;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** M2 (per-link byte budget, upload cap) and L4 (the e-mail variant is chosen by an explicit flag). */
class PortalByteBudgetTest extends PortalTestBase {

    @Autowired EmailTemplates templates;

    @Test
    void defaultsAre100MbAnd20UploadsAndExplicitValuesAreHonoured() throws Exception {
        String vendor = createVendor(member, "Budget", null);
        String w9 = typeId(member, "W9");
        JsonNode link = createLink(member, vendor, List.of(w9)).get("link");
        assertThat(link.get("maxTotalBytes").asLong()).isEqualTo(100L * 1024 * 1024);
        assertThat(link.get("usedBytes").asLong()).isZero();
        assertThat(link.get("maxUploads").asInt()).isEqualTo(20);

        JsonNode custom = createLink(member, vendor, List.of(w9), "maxTotalMb", 500, "maxUploads", 50).get("link");
        assertThat(custom.get("maxTotalBytes").asLong()).isEqualTo(500L * 1024 * 1024);
        assertThat(custom.get("maxUploads").asInt()).isEqualTo(50);
    }

    @Test
    void validationCapsUploadsAt50AndBytesAt500Mb() throws Exception {
        String vendor = createVendor(member, "Budget", null);
        String w9 = typeId(member, "W9");
        postLink(member, vendor, body(List.of(w9), "maxUploads", 51)).andExpect(status().isBadRequest());
        postLink(member, vendor, body(List.of(w9), "maxTotalMb", 501)).andExpect(status().isBadRequest());
        postLink(member, vendor, body(List.of(w9), "maxTotalMb", 0)).andExpect(status().isBadRequest());
        postLink(member, vendor, body(List.of(w9), "maxUploads", 50, "maxTotalMb", 1)).andExpect(status().isCreated());
        // the database enforces the same bounds
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("update vendor_upload_link set max_uploads = 51 where vendor_id = ?::uuid", vendor));
    }

    @Test
    void theByteBudgetIsClaimedAtomicallyAndAFailedUploadDoesNotConsumeIt() throws Exception {
        String vendor = createVendor(member, "Budget", null);
        String w9 = typeId(member, "W9");
        JsonNode created = createLink(member, vendor, List.of(w9), "maxTotalMb", 1);
        String token = tokenOf(created);
        String linkId = created.get("link").get("id").asString();

        portalUpload(token, file("a.pdf", pdfOfSize(600_000)), fields(w9, null, null)).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("select used_bytes from vendor_upload_link where id = ?::uuid", Long.class,
                linkId)).isEqualTo(600_000L);

        // a refused upload (bad extension) consumes nothing
        portalUpload(token, file("a.exe", pdfOfSize(1000)), fields(w9, null, null))
                .andExpect(status().isUnsupportedMediaType());
        // 600_000 + 600_000 > 1 MiB: refused as a spent budget, nothing stored
        portalUpload(token, file("b.pdf", pdfOfSize(600_000)), fields(w9, null, null))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://vendorflow.app/problems/portal-upload-limit"));
        assertThat(documentRows(vendor)).isEqualTo(1);
        assertThat(useCount(linkId)).isEqualTo(1);

        // what still fits is accepted: 600_000 + 400_000 = 1_000_000 <= 1_048_576
        portalUpload(token, file("c.pdf", pdfOfSize(400_000)), fields(w9, null, null)).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("select used_bytes from vendor_upload_link where id = ?::uuid", Long.class,
                linkId)).isEqualTo(1_000_000L);
        // the view only ever exposes the remaining UPLOADS count, not byte figures
        JsonNode info = json.readTree(portalGet(token).andReturn().getResponse().getContentAsString());
        assertThat(info.has("remainingBytes")).isFalse();
        assertThat(info.get("remainingUploads").asInt()).isEqualTo(18);
    }

    @Test
    void theEmailVariantFollowsTheExplicitPortalFlagNotThePresenceOfAToken() {
        Map<String, Object> base = Map.of("organizationName", "Org", "documentTypeName", "W-9",
                "vendorName", "Acme", "contactName", "Carl", "requesterName", "Dora", "token", "T".repeat(43));
        // a token alone no longer switches the template: this is the plain "reply with the attachment" request
        RenderedEmail plain = templates.render(NotificationKind.DOCUMENT_REQUEST, base);
        assertThat(plain.textBody()).doesNotContain("/portal#token=");
        // the explicit flag does
        Map<String, Object> flagged = new java.util.LinkedHashMap<>(base);
        flagged.put("portal", true);
        RenderedEmail portal = templates.render(NotificationKind.DOCUMENT_REQUEST, flagged);
        assertThat(portal.textBody()).contains("/portal#token=" + "T".repeat(43));
        // a flagged payload without its token fails loudly instead of silently falling back to the plain variant
        Map<String, Object> noToken = new java.util.LinkedHashMap<>(flagged);
        noToken.remove("token");
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> templates.render(NotificationKind.DOCUMENT_REQUEST, noToken)).getMessage()).doesNotContain("T");
    }
}
