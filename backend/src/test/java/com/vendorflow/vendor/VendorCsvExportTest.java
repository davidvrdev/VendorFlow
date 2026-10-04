package com.vendorflow.vendor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.document.domain.ReviewStatus;
import com.vendorflow.support.ApiClient;
import com.vendorflow.support.ComplianceFixtures;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.apache.commons.csv.CSVRecord;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

/** GET /vendors/export.csv and GET /vendors/import/template.csv. */
class VendorCsvExportTest extends VendorCsvTestBase {

    static final String EXPORT = VENDORS + "/export.csv";

    @Autowired EntityManagerFactory emf;

    @Test
    void exportIsUtf8WithBomCrlfAndDownloadHeaders() throws Exception {
        createVendor(owner, "Acme Plumbing", "Jane", "jane@acme.example", "Plumbing");

        MockHttpServletResponse response = owner.client().get(EXPORT).andExpect(status().isOk()).andReturn()
                .getResponse();
        byte[] bytes = response.getContentAsByteArray();

        assertThat(bytes).startsWith(0xEF, 0xBB, 0xBF);
        assertThat(response.getContentType()).startsWith("text/csv").containsIgnoringCase("utf-8");
        // The org time zone defaults to America/New_York: the date in the file name is the org-local date.
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("America/New_York")));
        assertThat(response.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"vendors-" + today
                + ".csv\"");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        String body = text(bytes);
        assertThat(body).startsWith("company_name,contact_name,email,phone,category,notes,status,compliance_status,"
                + "missing,expired,expiring,review_required,next_expiration\r\n");
        assertThat(body).endsWith("\r\n");
        // every record separator is CRLF: no bare LF anywhere in this simple export
        assertThat(body.replace("\r\n", "")).doesNotContain("\n").doesNotContain("\r");
    }

    @Test
    void fieldsWithCommasQuotesAndNewlinesAreQuotedAndRoundTripThroughAParser() throws Exception {
        String id = createVendor(owner, "Smith, Jones & \"Sons\"", "O'Neil", null, null);
        owner.client().put(VENDORS + "/" + id, java.util.Map.of("companyName", "Smith, Jones & \"Sons\"",
                "notes", "line one\nline two, with comma\nand \"quotes\"")).andExpect(status().isOk());

        byte[] bytes = export(owner, "");
        assertThat(text(bytes)).contains("\"Smith, Jones & \"\"Sons\"\"\"");
        List<CSVRecord> rows = records(bytes);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).get(0)).isEqualTo("Smith, Jones & \"Sons\"");
        assertThat(rows.get(1).get(5)).isEqualTo("line one\nline two, with comma\nand \"quotes\"");
    }

    @Test
    void cellsStartingWithFormulaCharactersArePrefixedWithAnApostrophe() throws Exception {
        String eq = createVendor(owner, "=SUM(A1:A9)", null, null, null);
        createVendor(owner, "+cmd", null, null, null);
        createVendor(owner, "-2+3", null, null, null);
        createVendor(owner, "@handle", null, null, null);
        createVendor(owner, "Safe Co", null, null, null);
        // Leading TAB / CR cannot be stored through the API (values are stripped): write them to the database
        // directly, as a legacy row or another tool could have.
        String tab = createVendor(owner, "Tab Co", null, null, null);
        String cr = createVendor(owner, "Cr Co", null, null, null);
        jdbc.update("update vendor set notes = ? where id = ?::uuid", "\t=cmd", tab);
        jdbc.update("update vendor set notes = ? where id = ?::uuid", "\r=cmd", cr);
        assertThat(eq).isNotBlank();

        List<CSVRecord> rows = records(export(owner, ""));
        List<String> names = rows.stream().skip(1).map(r -> r.get(0)).toList();
        assertThat(names).containsExactlyInAnyOrder("'=SUM(A1:A9)", "'+cmd", "'-2+3", "'@handle", "Safe Co", "Tab Co",
                "Cr Co");
        assertThat(rows.stream().filter(r -> r.get(0).equals("Tab Co")).findFirst().orElseThrow().get(5))
                .isEqualTo("'\t=cmd");
        assertThat(rows.stream().filter(r -> r.get(0).equals("Cr Co")).findFirst().orElseThrow().get(5))
                .isEqualTo("'\r=cmd");
    }

    @Test
    void complianceColumnsDescribeEachVendor() throws Exception {
        String org = owner.organizationId();
        ComplianceFixtures fx = new ComplianceFixtures(jdbc);
        createVendor(owner, "A Needs Docs", null, null, null); // 3 default requirements, nothing uploaded
        UUID good = fx.vendor(org, "B Compliant");
        UUID expiring = fx.vendor(org, "C Expiring");
        UUID none = fx.vendor(org, "D No Requirements");
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("America/New_York")));
        fx.requirement(org, good, "W9", ReviewStatus.APPROVED, null);
        fx.requirement(org, expiring, "COI", ReviewStatus.APPROVED, today.plusDays(10), Instant.now());
        assertThat(none).isNotNull();

        List<CSVRecord> rows = records(export(owner, ""));
        CSVRecord a = rows.get(1);
        assertThat(col(a, "company_name")).isEqualTo("A Needs Docs");
        assertThat(col(a, "compliance_status")).isEqualTo("NON_COMPLIANT");
        assertThat(col(a, "missing")).isEqualTo("3");
        assertThat(col(a, "expired")).isEqualTo("0");
        assertThat(col(a, "next_expiration")).isEmpty();
        assertThat(col(rows.get(2), "compliance_status")).isEqualTo("COMPLIANT");
        assertThat(col(rows.get(2), "missing")).isEqualTo("0");
        CSVRecord c = rows.get(3);
        assertThat(col(c, "compliance_status")).isEqualTo("ATTENTION");
        assertThat(col(c, "expiring")).isEqualTo("1");
        assertThat(col(c, "next_expiration")).isEqualTo(today.plusDays(10).toString());
        assertThat(col(rows.get(4), "compliance_status")).isEqualTo("COMPLIANT");
    }

    @Test
    void filtersAreTheSameAsTheListEndpoint() throws Exception {
        createVendor(owner, "Alpha Plumbing", "Pat", "pat@a.example", "Plumbing");
        createVendor(owner, "Bravo Electric", null, null, "Electrical");
        String gone = createVendor(owner, "Charlie Old", null, null, "Plumbing");
        owner.client().post(VENDORS + "/" + gone + "/deactivate", null).andExpect(status().isOk());

        assertThat(names(owner, "")).containsExactly("Alpha Plumbing", "Bravo Electric"); // default ACTIVE, by name
        assertThat(names(owner, "?status=ALL")).containsExactly("Alpha Plumbing", "Bravo Electric", "Charlie Old");
        assertThat(names(owner, "?status=INACTIVE")).containsExactly("Charlie Old");
        assertThat(names(owner, "?status=ALL&category=plumbing")).containsExactly("Alpha Plumbing", "Charlie Old");
        assertThat(names(owner, "?q=pat")).containsExactly("Alpha Plumbing");
        assertThat(names(owner, "?compliance=COMPLIANT")).isEmpty();
        assertThat(names(owner, "?compliance=NON_COMPLIANT")).containsExactly("Alpha Plumbing", "Bravo Electric");
        // the same rows the list endpoint shows for the same query
        owner.client().get(VENDORS + "?status=ALL&category=plumbing").andExpect(jsonPath("$.totalItems").value(2));

        owner.client().get(EXPORT + "?status=BOGUS").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("status"));
        owner.client().get(EXPORT + "?compliance=BOGUS").andExpect(status().isBadRequest());
    }

    @Test
    void exportRefusesMoreThanTenThousandRowsButAcceptsExactlyTenThousand() throws Exception {
        insertVendors(owner.organizationId(), 10_000);
        byte[] ok = owner.client().get(EXPORT).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsByteArray();
        assertThat(records(ok)).hasSize(10_001);

        insertVendors(owner.organizationId(), 1);
        owner.client().get(EXPORT).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Too many vendors to export"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("filter")));
        // narrowing the export works again
        owner.client().get(EXPORT + "?q=bulk%2000001").andExpect(status().isOk());
    }

    @Test
    void everyRoleMayExportAndUnauthenticatedMayNot() throws Exception {
        createVendor(owner, "Visible Co", null, null, null);
        for (var who : List.of(owner, admin, member, viewer)) {
            assertThat(names(who, "")).containsExactly("Visible Co");
        }
        accounts.newClient().get(EXPORT).andExpect(status().isUnauthorized());
    }

    @Test
    void exportNeverContainsAnotherOrganizationsVendors() throws Exception {
        createVendor(owner, "Org A Secret Vendor", "a@a.example", null, "Hidden");
        createVendor(other, "Org B Vendor", null, null, null);

        String forB = text(export(other, "?status=ALL"));
        assertThat(forB).contains("Org B Vendor").doesNotContain("Org A Secret Vendor").doesNotContain("Hidden");
        assertThat(names(other, "?q=secret")).isEmpty();
        assertThat(names(other, "?category=Hidden&status=ALL")).isEmpty();
        assertThat(names(owner, "?status=ALL")).containsExactly("Org A Secret Vendor");
    }

    @Test
    void exportIsAuditedWithFiltersAndRowCountButNoVendorData() throws Exception {
        createVendor(owner, "Audited Vendor Name", "Private Person", "private@x.example", "Cat");
        export(viewer, "?status=ALL&q=audited");

        String metadata = jdbc.queryForObject("""
                select metadata::text from audit_event
                where organization_id = ?::uuid and action = 'vendor.exported'""", String.class,
                owner.organizationId());
        assertThat(metadata).contains("\"rowCount\": 1").contains("\"status\": \"ALL\"").contains("audited");
        assertThat(metadata).doesNotContain("Audited Vendor Name").doesNotContain("Private Person")
                .doesNotContain("private@x.example");
        assertThat(jdbc.queryForObject("""
                select actor_user_id::text from audit_event
                where organization_id = ?::uuid and action = 'vendor.exported'""", String.class,
                owner.organizationId())).isEqualTo(viewer.userId());
    }

    @Test
    void exportExecutesABoundedNumberOfStatementsRegardlessOfVendorCount() throws Exception {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        for (int i = 0; i < 3; i++) {
            createVendor(owner, "Few " + i, "c", "c@x.example", "Cat");
        }
        long few = statementsFor(stats, owner.client());
        insertVendors(owner.organizationId(), 300);
        long many = statementsFor(stats, owner.client());

        assertThat(many).as("statements with 303 vendors vs 3 vendors").isEqualTo(few);
        // tenant lookup + org settings + the single export query + audit insert
        assertThat(many).isLessThanOrEqualTo(6);
    }

    @Test
    void templateHasTheImportHeaderAndOneExampleRow() throws Exception {
        MockHttpServletResponse response = owner.client().get(VENDORS + "/import/template.csv")
                .andExpect(status().isOk()).andReturn().getResponse();
        byte[] bytes = response.getContentAsByteArray();
        assertThat(bytes).startsWith(0xEF, 0xBB, 0xBF);
        assertThat(response.getHeader("Content-Disposition")).contains("attachment");
        List<CSVRecord> rows = records(bytes);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).toList()).containsExactly("company_name", "contact_name", "email", "phone",
                "category", "notes", "status");
        assertThat(rows.get(1).get(0)).isNotBlank();

        // the template round-trips through the importer without errors
        var preview = previewOk(owner, bytes);
        assertThat(preview.get("summary").get("create").asInt()).isEqualTo(1);
        assertThat(preview.get("summary").get("error").asInt()).isZero();
    }

    @Test
    void templateRequiresTheImportPermission() throws Exception {
        for (var who : List.of(member, viewer)) {
            who.client().get(VENDORS + "/import/template.csv").andExpect(status().isForbidden());
        }
        admin.client().get(VENDORS + "/import/template.csv").andExpect(status().isOk());
        accounts.newClient().get(VENDORS + "/import/template.csv").andExpect(status().isUnauthorized());
    }

    // ---- round trip: an export can be re-imported untouched ----

    @Test
    void exportedFileIsPreviewedAsAllUnchanged() throws Exception {
        createVendor(owner, "Acme, Inc.", "Jane \"JJ\" Smith", "jane@acme.example", "Plumbing");
        String phoneVendor = createVendor(owner, "+Plus Phone Co", null, null, null);
        owner.client().put(VENDORS + "/" + phoneVendor, java.util.Map.of("companyName", "+Plus Phone Co", "phone",
                "+1 (555) 010-0100", "notes", "multi\nline note, with \"quotes\"")).andExpect(status().isOk());
        String inactive = createVendor(owner, "Dormant Co", null, null, null);
        owner.client().post(VENDORS + "/" + inactive + "/deactivate", null).andExpect(status().isOk());

        byte[] exported = export(owner, "?status=ALL");
        var preview = previewOk(owner, exported);

        assertThat(preview.get("summary").get("total").asInt()).isEqualTo(3);
        assertThat(preview.get("summary").get("unchanged").asInt()).isEqualTo(3);
        assertThat(preview.get("summary").get("create").asInt()).isZero();
        assertThat(preview.get("summary").get("update").asInt()).isZero();
        assertThat(preview.get("summary").get("error").asInt()).isZero();
    }

    // ---- helpers ----

    private static final List<String> COLUMNS = List.of("company_name", "contact_name", "email", "phone", "category",
            "notes", "status", "compliance_status", "missing", "expired", "expiring", "review_required",
            "next_expiration");

    private static String col(CSVRecord r, String name) {
        return r.get(COLUMNS.indexOf(name));
    }

    private List<String> names(com.vendorflow.support.TestAccounts.Account who, String query) throws Exception {
        return records(export(who, query)).stream().skip(1).map(r -> r.get(0)).toList();
    }

    private long statementsFor(Statistics stats, ApiClient client) throws Exception {
        stats.clear();
        client.get(EXPORT).andExpect(status().isOk());
        return stats.getPrepareStatementCount();
    }

    /** Bulk fixture: {@code count} ACTIVE vendors without requirements, names "bulk 00001"... */
    private void insertVendors(String org, int count) {
        Integer existing = jdbc.queryForObject("select count(*) from vendor where organization_id = ?::uuid",
                Integer.class, org);
        jdbc.update("""
                insert into vendor (id, organization_id, company_name, status, created_at, updated_at)
                select gen_random_uuid(), ?::uuid, 'bulk ' || lpad((? + g)::text, 5, '0'), 'ACTIVE', now(), now()
                from generate_series(1, ?) g""", org, existing == null ? 0 : existing, count);
    }


    // ---- export rate limit (security fix L1): 10 per 10 minutes per user, before the query ----

    @Test
    void exportsAreLimitedToTenPerTenMinutesPerUser() throws Exception {
        for (int i = 0; i < 10; i++) {
            owner.client().get(EXPORT).andExpect(status().isOk());
        }
        owner.client().get(EXPORT).andExpect(status().isTooManyRequests()).andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().exists("Retry-After"));
        admin.client().get(EXPORT).andExpect(status().isOk()); // the budget is per user

        clock.advance(java.time.Duration.ofMinutes(5)); // still inside the 10 minute window (not the 1 minute default)
        owner.client().get(EXPORT).andExpect(status().isTooManyRequests());
        clock.advance(java.time.Duration.ofMinutes(6));
        owner.client().get(EXPORT).andExpect(status().isOk());
    }
}
