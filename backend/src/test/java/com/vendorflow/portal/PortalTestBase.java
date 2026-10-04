package com.vendorflow.portal;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.support.ApiClient;
import com.vendorflow.support.IntegrationTest;
import com.vendorflow.support.TestAccounts;
import com.vendorflow.support.TestAccounts.Account;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fixtures of the vendor portal tests: one organization with all four roles (owner and admin verified, so they are
 * e-mail recipients), one unrelated organization, and a cookie-less "vendor browser" for the public endpoints.
 */
abstract class PortalTestBase extends IntegrationTest {

    static final byte[] PDF = ("%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n")
            .getBytes();
    private static final Pattern TOKEN = Pattern.compile("#token=([A-Za-z0-9_-]+)$");

    @Autowired protected MockMvc mvc;
    @Autowired protected JsonMapper json;
    @Autowired protected JdbcTemplate jdbc;

    protected TestAccounts accounts;
    protected Account owner;
    protected Account admin;
    protected Account member;
    protected Account viewer;
    protected Account other; // owner of an unrelated organization
    protected String orgId;

    @BeforeEach
    void setUpPortalAccounts() throws Exception {
        accounts = new TestAccounts(mvc, json, jdbc);
        owner = accounts.verified(accounts.signup(TestAccounts.uniqueEmail(), TestAccounts.PASSWORD, "Dora Owner",
                "Portal Org " + UUID.randomUUID()));
        orgId = owner.organizationId();
        admin = accounts.verified(accounts.memberOf(orgId, "ADMIN", "Adam Admin"));
        member = accounts.memberOf(orgId, "MEMBER", "Mia Member");
        viewer = accounts.memberOf(orgId, "VIEWER", "Vic Viewer");
        other = accounts.verified(accounts.signup(TestAccounts.uniqueEmail(), TestAccounts.PASSWORD, "Olga Other",
                "Other Portal Org " + UUID.randomUUID()));
    }

    // ---- staff fixtures ----

    protected String createVendor(Account who, String name, String email) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("companyName", name + " " + UUID.randomUUID());
        if (email != null) {
            body.put("email", email);
        }
        return json.readTree(who.client().post("/api/v1/vendors", body).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString()).get("id").asString();
    }

    protected String typeId(Account who, String code) throws Exception {
        JsonNode types = json.readTree(who.client().get("/api/v1/document-types").andReturn().getResponse()
                .getContentAsString());
        for (JsonNode t : types) {
            if (t.get("code").asString().equals(code)) {
                return t.get("id").asString();
            }
        }
        throw new AssertionError("no type " + code);
    }

    protected static String linksPath(String vendorId) {
        return "/api/v1/vendors/" + vendorId + "/upload-links";
    }

    /** POST upload-links as {@code who}; returns the raw ResultActions. */
    protected ResultActions postLink(Account who, String vendorId, Map<String, Object> body) throws Exception {
        return who.client().post(linksPath(vendorId), body);
    }

    protected static Map<String, Object> body(List<String> typeIds, Object... extras) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("documentTypeIds", typeIds);
        for (int i = 0; i < extras.length; i += 2) {
            m.put((String) extras[i], extras[i + 1]);
        }
        return m;
    }

    /** Creates a link (expects 201) and returns the response JSON. */
    protected JsonNode createLink(Account who, String vendorId, List<String> typeIds, Object... extras)
            throws Exception {
        return json.readTree(postLink(who, vendorId, body(typeIds, extras)).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString());
    }

    /** The raw token of a creation response (what the vendor's email link carries in its fragment). */
    protected static String tokenOf(JsonNode created) {
        Matcher m = TOKEN.matcher(created.get("url").asString());
        if (!m.find()) {
            throw new AssertionError("no token in url");
        }
        return m.group(1);
    }

    // ---- vendor (public) side ----

    /** A cookie-less browser: no session, no CSRF cookie. */
    protected ApiClient vendorBrowser() {
        return new ApiClient(mvc, json).remoteAddr("198.51.100." + (1 + (int) (Math.random() * 250)));
    }

    static final String PORTAL = "/api/v1/portal/link";
    static final String PORTAL_DOCS = "/api/v1/portal/link/documents";

    /** A cookie-less vendor browser presenting the token in X-Portal-Token. */
    protected ApiClient vendorBrowser(String token) {
        ApiClient c = vendorBrowser();
        return token == null ? c : c.header("X-Portal-Token", token);
    }

    protected ResultActions portalGet(String token) throws Exception {
        return vendorBrowser(token).perform(HttpMethod.GET, PORTAL, null, false);
    }

    protected ResultActions portalUpload(String token, MockMultipartFile file, Map<String, String> fields)
            throws Exception {
        return vendorBrowser(token).postMultipartWithoutCsrf(PORTAL_DOCS, file, fields);
    }

    protected static MockMultipartFile file(String filename, byte[] content) {
        return new MockMultipartFile("file", filename, "application/octet-stream", content);
    }

    protected static Map<String, String> fields(String typeId, String issue, String expiration) {
        Map<String, String> m = new LinkedHashMap<>();
        if (typeId != null) {
            m.put("documentTypeId", typeId);
        }
        if (issue != null) {
            m.put("issueDate", issue);
        }
        if (expiration != null) {
            m.put("expirationDate", expiration);
        }
        return m;
    }

    /** A valid PDF of exactly {@code size} bytes. */
    protected static byte[] pdfOfSize(int size) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(size);
        out.writeBytes("%PDF-1.4\n".getBytes());
        while (out.size() < size) {
            out.write('a');
        }
        return out.toByteArray();
    }

    // ---- database probes ----

    protected int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    protected int documentRows(String vendorId) {
        return count("select count(*) from document where vendor_id = ?::uuid", vendorId);
    }

    protected int useCount(String linkId) {
        return count("select use_count from vendor_upload_link where id = ?::uuid", linkId);
    }
}
