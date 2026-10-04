package com.vendorflow.vendor;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.support.ApiClient;
import com.vendorflow.support.IntegrationTest;
import com.vendorflow.support.TestAccounts;
import com.vendorflow.support.TestAccounts.Account;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Shared setup and helpers of the CSV export / import tests: organization A with every role, plus organization B. */
abstract class VendorCsvTestBase extends IntegrationTest {

    static final String VENDORS = "/api/v1/vendors";
    static final String PREVIEW = VENDORS + "/import/preview";
    static final String HEADER = "company_name,contact_name,email,phone,category,notes,status\r\n";

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;

    TestAccounts accounts;
    Account owner;
    Account admin;
    Account member;
    Account viewer;
    Account other;

    @BeforeEach
    void setUpAccounts() throws Exception {
        accounts = new TestAccounts(mvc, json, jdbc);
        owner = accounts.signup(TestAccounts.uniqueEmail(), TestAccounts.PASSWORD, "Dora Owner",
                "Csv Org " + UUID.randomUUID());
        admin = accounts.memberOf(owner.organizationId(), "ADMIN", "Ada Admin");
        member = accounts.memberOf(owner.organizationId(), "MEMBER", "Mia Member");
        viewer = accounts.memberOf(owner.organizationId(), "VIEWER", "Vic Viewer");
        other = accounts.signup(TestAccounts.uniqueEmail(), TestAccounts.PASSWORD, "Olga Other",
                "Csv Other " + UUID.randomUUID());
    }

    String createVendor(Account who, String name, String contact, String email, String category) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("companyName", name);
        b.put("contactName", contact);
        b.put("email", email);
        b.put("category", category);
        return json.readTree(who.client().post(VENDORS, b).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString()).get("id").asString();
    }

    ResultActions upload(Account who, byte[] content, String filename) throws Exception {
        return who.client().postMultipart(PREVIEW, new MockMultipartFile("file", filename, "text/csv", content),
                Map.of());
    }

    ResultActions upload(Account who, String csv) throws Exception {
        return upload(who, csv.getBytes(StandardCharsets.UTF_8), "vendors.csv");
    }

    JsonNode previewOk(Account who, String csv) throws Exception {
        return json.readTree(upload(who, csv).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    JsonNode previewOk(Account who, byte[] csv) throws Exception {
        return json.readTree(upload(who, csv, "vendors.csv").andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    ResultActions commit(Account who, String importId) throws Exception {
        return who.client().post(VENDORS + "/import/" + importId + "/commit", null);
    }

    byte[] export(Account who, String query) throws Exception {
        return who.client().get(VENDORS + "/export.csv" + query).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsByteArray();
    }

    /** Decoded export body without the BOM. */
    static String text(byte[] bytes) {
        String s = new String(bytes, StandardCharsets.UTF_8);
        return s.startsWith("﻿") ? s.substring(1) : s;
    }

    static List<CSVRecord> records(byte[] bytes) throws Exception {
        try (CSVParser p = CSVFormat.DEFAULT.parse(new StringReader(text(bytes)))) {
            return p.getRecords();
        }
    }

    /** The row of the preview with this number. */
    static JsonNode row(JsonNode preview, int rowNumber) {
        for (JsonNode r : preview.get("rows")) {
            if (r.get("rowNumber").asInt() == rowNumber) {
                return r;
            }
        }
        throw new AssertionError("no row " + rowNumber + " in " + preview);
    }

    static boolean hasError(JsonNode row, String field) {
        for (JsonNode e : row.get("errors")) {
            if (field.equals(e.get("field").asString())) {
                return true;
            }
        }
        return false;
    }

    ApiClient client(Account who) {
        return who.client();
    }
}
