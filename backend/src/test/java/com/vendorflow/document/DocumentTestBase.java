package com.vendorflow.document;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.support.IntegrationTest;
import com.vendorflow.support.TestAccounts;
import com.vendorflow.support.TestAccounts.Account;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Shared fixtures of the document tests: one organization with all four roles, and one unrelated organization. */
abstract class DocumentTestBase extends IntegrationTest {

    static final String VENDORS = "/api/v1/vendors";
    static final String DOCUMENTS = "/api/v1/documents";

    static final byte[] PDF = ("%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n")
            .getBytes();
    static final byte[] PNG = concat(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A},
            "fake-png-body".getBytes());
    static final byte[] JPEG = concat(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0},
            "fake-jpeg-body".getBytes());

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;

    TestAccounts accounts;
    Account owner;
    Account admin;
    Account member;
    Account viewer;
    Account other; // owner of an unrelated organization
    String orgId;

    @BeforeEach
    void setUpAccounts() throws Exception {
        accounts = new TestAccounts(mvc, json, jdbc);
        owner = accounts.signup(TestAccounts.uniqueEmail(), TestAccounts.PASSWORD, "Dora Owner",
                "Docs Org " + UUID.randomUUID());
        orgId = owner.organizationId();
        admin = accounts.memberOf(orgId, "ADMIN", "Adam Admin");
        member = accounts.memberOf(orgId, "MEMBER", "Mia Member");
        viewer = accounts.memberOf(orgId, "VIEWER", "Vic Viewer");
        other = accounts.signup(TestAccounts.uniqueEmail(), TestAccounts.PASSWORD, "Olga Other",
                "Other Docs Org " + UUID.randomUUID());
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** A valid PDF of exactly {@code size} bytes (header + padding). */
    static byte[] pdfOfSize(int size) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(size);
        out.writeBytes("%PDF-1.4\n".getBytes());
        while (out.size() < size) {
            out.write('a');
        }
        return out.toByteArray();
    }

    // ---- vendors and types ----

    String createVendor(Account who, String name) throws Exception {
        return json.readTree(who.client().post(VENDORS, Map.of("companyName", name + " " + UUID.randomUUID()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    JsonNode vendor(Account who, String vendorId) throws Exception {
        return json.readTree(who.client().get(VENDORS + "/" + vendorId).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
    }

    String typeId(Account who, String code) throws Exception {
        JsonNode types = json.readTree(who.client().get("/api/v1/document-types").andReturn().getResponse()
                .getContentAsString());
        for (JsonNode t : types) {
            if (t.get("code").asString().equals(code)) {
                return t.get("id").asString();
            }
        }
        throw new AssertionError("no type " + code);
    }

    // ---- uploads ----

    static MockMultipartFile file(String filename, byte[] content) {
        return new MockMultipartFile("file", filename, "application/octet-stream", content);
    }

    static Map<String, String> fields(String typeId, String issue, String expiration) {
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

    ResultActions upload(Account who, String vendorId, MockMultipartFile file, Map<String, String> fields)
            throws Exception {
        return who.client().postMultipart(VENDORS + "/" + vendorId + "/documents", file, fields);
    }

    /** Uploads a valid PDF of the given type (COI needs an expiration date) and returns the DocumentSummary. */
    JsonNode uploadPdf(Account who, String vendorId, String typeId, String filename) throws Exception {
        return uploadOk(who, vendorId, file(filename, PDF), fields(typeId, "2026-01-01", "2027-01-01"));
    }

    JsonNode uploadOk(Account who, String vendorId, MockMultipartFile file, Map<String, String> fields)
            throws Exception {
        return json.readTree(upload(who, vendorId, file, fields).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString());
    }

    JsonNode getDoc(Account who, String documentId) throws Exception {
        return json.readTree(who.client().get(DOCUMENTS + "/" + documentId).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
    }

    int auditCount(String action, String entityId) {
        return jdbc.queryForObject("select count(*) from audit_event where action = ? and entity_id = ?::uuid",
                Integer.class, action, entityId);
    }
}
