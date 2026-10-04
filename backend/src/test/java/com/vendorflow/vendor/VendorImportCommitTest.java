package com.vendorflow.vendor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.support.TestAccounts.Account;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** POST /vendors/import/{id}/commit. */
class VendorImportCommitTest extends VendorCsvTestBase {

    private String preview(Account who, String csv) throws Exception {
        return previewOk(who, csv).get("importId").asString();
    }

    private int vendorCount(Account who) {
        return jdbc.queryForObject("select count(*) from vendor where organization_id = ?::uuid", Integer.class,
                who.organizationId());
    }

    private String importStatus(String importId) {
        return jdbc.queryForObject("select status from vendor_import where id = ?::uuid", String.class, importId);
    }

    @Test
    void commitCreatesUpdatesAndLeavesUnchangedRowsAloneWithDefaultRequirementsAndAudit() throws Exception {
        String keepId = createVendor(owner, "Keep Data Co", "Old Contact", "old@x.example", "Old Cat");
        createVendor(owner, "Same Co", "Sam", "sam@x.example", "Cat");
        String importId = preview(owner, HEADER
                + "Brand New Co,Nina,nina@x.example,555-0101,Plumbing,\"two\nlines\",\r\n"
                + "Dormant New Co,,,,,,INACTIVE\r\n"
                + "keep data co,,,,New Cat,,\r\n"
                + "Same Co,Sam,sam@x.example,,Cat,,\r\n");

        commit(owner, importId).andExpect(status().isOk()).andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.updated").value(1)).andExpect(jsonPath("$.unchanged").value(1));

        // creates: values stored, default requirements attached (3 required-by-default types), status honoured
        Map<String, Object> created = jdbc.queryForMap("""
                select id, contact_name, email, phone, category, notes, status from vendor
                where organization_id = ?::uuid and company_name = 'Brand New Co'""", owner.organizationId());
        assertThat(created).containsEntry("contact_name", "Nina").containsEntry("phone", "555-0101")
                .containsEntry("notes", "two\nlines").containsEntry("status", "ACTIVE");
        assertThat(requirementCount(created.get("id"))).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                select status from vendor where organization_id = ?::uuid and company_name = 'Dormant New Co'""",
                String.class, owner.organizationId())).isEqualTo("INACTIVE");

        // update: only non-empty cells changed (name casing, category); contact and e-mail kept
        Map<String, Object> kept = jdbc.queryForMap("select * from vendor where id = ?::uuid", keepId);
        assertThat(kept).containsEntry("company_name", "keep data co").containsEntry("category", "New Cat")
                .containsEntry("contact_name", "Old Contact").containsEntry("email", "old@x.example");

        assertThat(vendorCount(owner)).isEqualTo(4);
        assertThat(importStatus(importId)).isEqualTo("COMMITTED");
        // visible through the normal API
        owner.client().get(VENDORS + "?status=ALL").andExpect(jsonPath("$.totalItems").value(4));
    }

    @Test
    void commitWritesAuditEventsWithSourceAndImportIdAndASummary() throws Exception {
        String keepId = createVendor(owner, "Audit Update Co", "A", null, null);
        String importId = preview(owner, HEADER + "Audit New Co,,,,,,\r\nAudit Update Co,B,,,,,\r\n"
                + "Audit Update Co2,,,,,,\r\n");
        commit(owner, importId).andExpect(status().isOk());

        List<Map<String, Object>> created = jdbc.queryForList("""
                select metadata::text as m from audit_event
                where organization_id = ?::uuid and action = 'vendor.created' and metadata ->> 'source' = 'csv_import'
                """, owner.organizationId());
        assertThat(created).hasSize(2);
        assertThat(created.get(0).get("m").toString()).contains("\"importId\": \"" + importId + "\"");

        String updated = jdbc.queryForObject("""
                select metadata::text from audit_event
                where organization_id = ?::uuid and action = 'vendor.updated' and entity_id = ?::uuid""",
                String.class, owner.organizationId(), keepId);
        assertThat(updated).contains("\"source\": \"csv_import\"").contains("\"importId\": \"" + importId + "\"")
                .contains("contactName");

        String summary = jdbc.queryForObject("""
                select metadata::text from audit_event
                where organization_id = ?::uuid and action = 'vendor.imported' and entity_id = ?::uuid""",
                String.class, owner.organizationId(), importId);
        assertThat(summary).contains("\"created\": 2").contains("\"updated\": 1").contains("\"unchanged\": 0");
        assertThat(jdbc.queryForObject("""
                select count(*) from audit_event where organization_id = ?::uuid and action = 'vendor.imported'""",
                Integer.class, owner.organizationId())).isEqualTo(1);

        // the vendor history API shows the field changes of an import update
        owner.client().get(VENDORS + "/" + keepId + "/history").andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].action").value("vendor.updated"))
                .andExpect(jsonPath("$.items[0].changes.contactName.after").value("B"));
    }

    @Test
    void committingTwiceIsRejectedAndAppliesNothingTheSecondTime() throws Exception {
        String importId = preview(owner, HEADER + "Once Co,,,,,,\r\n");
        commit(owner, importId).andExpect(status().isOk());
        commit(owner, importId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Import already committed"));
        commit(admin, importId).andExpect(status().isConflict());
        assertThat(vendorCount(owner)).isEqualTo(1);
    }

    @Test
    void anExpiredPreviewCannotBeCommitted() throws Exception {
        String importId = preview(owner, HEADER + "Late Co,,,,,,\r\n");
        clock.advance(Duration.ofMinutes(59));
        // still valid just before the hour is over: preview again to keep the other test below independent
        String stillValid = preview(owner, HEADER + "Just In Time Co,,,,,,\r\n");
        commit(owner, stillValid).andExpect(status().isOk());

        clock.advance(Duration.ofMinutes(2)); // the first preview is now 61 minutes old
        commit(owner, importId).andExpect(status().isGone()).andExpect(jsonPath("$.title").value("Import expired"));
        assertThat(vendorCount(owner)).isEqualTo(1); // only "Just In Time Co"
        assertThat(importStatus(importId)).isEqualTo("PREVIEWED");
    }

    @Test
    void aPreviewWithErrorRowsCannotBeCommitted() throws Exception {
        String importId = preview(owner, HEADER + "Good Co,,,,,,\r\nBad Email Co,,nope,,,,\r\n");
        commit(owner, importId).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Import has errors"));
        assertThat(vendorCount(owner)).isZero();
        assertThat(importStatus(importId)).isEqualTo("PREVIEWED");
    }

    @Test
    void aVendorCreatedAfterThePreviewMakesCommitFailAndApplyNothing() throws Exception {
        String importId = preview(owner, HEADER + "Innocent Co,,,,,,\r\nRace Co,,,,,,\r\n");
        createVendor(owner, "race co", null, null, null); // someone else created it meanwhile

        commit(owner, importId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Data changed since preview"));

        assertThat(vendorCount(owner)).isEqualTo(1); // only the manually created one: "Innocent Co" was rolled back
        assertThat(jdbc.queryForObject("""
                select count(*) from audit_event where organization_id = ?::uuid
                and (action = 'vendor.imported' or metadata ->> 'source' = 'csv_import')""", Integer.class,
                owner.organizationId())).isZero();
        assertThat(importStatus(importId)).isEqualTo("PREVIEWED");
    }

    @Test
    void anEditOfAMatchedVendorAfterThePreviewMakesCommitFail() throws Exception {
        String id = createVendor(owner, "Edited Meanwhile Co", null, "before@x.example", null);
        String importId = preview(owner, HEADER + "Edited Meanwhile Co,,after@x.example,,,,\r\n"); // an UPDATE
        owner.client().put(VENDORS + "/" + id, Map.of("companyName", "Edited Meanwhile Co", "email",
                "after@x.example")).andExpect(status().isOk()); // now there is nothing left to update

        commit(owner, importId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Data changed since preview"));
        assertThat(importStatus(importId)).isEqualTo("PREVIEWED");
    }

    @Test
    void aMatchedVendorDeletedFromThePreviewsPointOfViewIsDetectedWhenRenamed() throws Exception {
        String id = createVendor(owner, "Rename Target Co", null, null, null);
        String importId = preview(owner, HEADER + "Rename Target Co,Contact,,,,,\r\n");
        owner.client().put(VENDORS + "/" + id, Map.of("companyName", "Renamed Elsewhere Co")).andExpect(status().isOk());

        // the name no longer matches: the row would now be a CREATE, not the previewed UPDATE
        commit(owner, importId).andExpect(status().isConflict());
        assertThat(vendorCount(owner)).isEqualTo(1);
    }

    @Test
    void importOfAnotherOrganizationIsA404AndStaysUntouched() throws Exception {
        String importId = preview(owner, HEADER + "Org A Only Co,,,,,,\r\n");

        commit(other, importId).andExpect(status().isNotFound());
        commit(other, UUID.randomUUID().toString()).andExpect(status().isNotFound());

        assertThat(importStatus(importId)).isEqualTo("PREVIEWED");
        assertThat(vendorCount(other)).isZero();
        commit(owner, importId).andExpect(status().isOk());
        assertThat(vendorCount(owner)).isEqualTo(1);
        assertThat(vendorCount(other)).isZero();
    }

    @Test
    void aPreviewCreatedByOneAdminCanBeCommittedByAnotherAdminOfTheSameOrganization() throws Exception {
        String importId = preview(owner, HEADER + "Shared Review Co,,,,,,\r\n");
        commit(admin, importId).andExpect(status().isOk());
        assertThat(vendorCount(owner)).isEqualTo(1);
    }

    @Test
    void memberAndViewerCannotCommitAndUnauthenticatedIs401() throws Exception {
        String importId = preview(owner, HEADER + "Denied Co,,,,,,\r\n");
        commit(member, importId).andExpect(status().isForbidden());
        commit(viewer, importId).andExpect(status().isForbidden());
        accounts.newClient().post(VENDORS + "/import/" + importId + "/commit", null)
                .andExpect(status().isUnauthorized());
        assertThat(vendorCount(owner)).isZero();
        assertThat(importStatus(importId)).isEqualTo("PREVIEWED");
    }

    @Test
    void aMalformedImportIdIs400Not500() throws Exception {
        commit(owner, "not-a-uuid").andExpect(status().isBadRequest());
    }

    @Test
    void concurrentCommitsOfTheSameImportApplyExactlyOnce() throws Exception {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 0; i < 30; i++) {
            csv.append("Concurrent Co ").append(i).append(",,,,,,\r\n");
        }
        String importId = preview(owner, csv.toString());

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Account> callers = List.of(owner, admin, owner, admin);
            List<Future<Integer>> results = new ArrayList<>();
            for (Account who : callers) {
                // owner/admin appear twice: use a copy of the client so two threads never share a cookie jar
                var client = who.client().copy();
                Callable<Integer> task = () -> {
                    go.await();
                    return client.post(VENDORS + "/import/" + importId + "/commit", null).andReturn().getResponse()
                            .getStatus();
                };
                results.add(pool.submit(task));
            }
            go.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : results) {
                codes.add(f.get());
            }
            assertThat(codes.stream().filter(c -> c == 200)).hasSize(1);
            assertThat(codes.stream().filter(c -> c == 409)).hasSize(3);
        } finally {
            pool.shutdownNow();
        }
        assertThat(vendorCount(owner)).isEqualTo(30);
        assertThat(jdbc.queryForObject("""
                select count(*) from audit_event where organization_id = ?::uuid and action = 'vendor.imported'""",
                Integer.class, owner.organizationId())).isEqualTo(1);
    }

    @Test
    void importedVendorsAreFullCitizensOfTheVendorApi() throws Exception {
        String importId = preview(owner, HEADER + "Citizen Co,Cia,cia@x.example,,Cat,,\r\n");
        commit(owner, importId).andExpect(status().isOk());
        JsonNode list = json.readTree(owner.client().get(VENDORS + "?q=citizen").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(list.get("items").get(0).get("requirementCount").asInt()).isEqualTo(3);
        assertThat(list.get("items").get(0).get("compliance").get("missing").asInt()).isEqualTo(3);
        // and a second import of the same file is now all UNCHANGED
        JsonNode again = previewOk(owner, HEADER + "Citizen Co,Cia,cia@x.example,,Cat,,\r\n");
        assertThat(again.get("summary").get("unchanged").asInt()).isEqualTo(1);
    }

    private int requirementCount(Object vendorId) {
        return jdbc.queryForObject("select count(*) from vendor_requirement where vendor_id = ?", Integer.class,
                vendorId);
    }

    // ---- security fixes: re-validation, locking, data retention ----

    @Test
    void commitClearsTheStoredRowsButKeepsTheSummary() throws Exception {
        String importId = preview(owner, HEADER + "Retention Co,Rita,rita@x.example,,,,\r\n");
        commit(owner, importId).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("select jsonb_array_length(rows) from vendor_import where id = ?::uuid",
                Integer.class, importId)).isZero();
        assertThat(jdbc.queryForObject("select summary->>'total' from vendor_import where id = ?::uuid",
                String.class, importId)).isEqualTo("1");
        assertThat(jdbc.queryForObject("select rows::text from vendor_import where id = ?::uuid", String.class,
                importId)).doesNotContain("rita");
    }

    @Test
    void anUncommittedPreviewKeepsItsRows() throws Exception {
        String importId = preview(owner, HEADER + "Pending Co,,,,,,\r\n");
        assertThat(jdbc.queryForObject("select jsonb_array_length(rows) from vendor_import where id = ?::uuid",
                Integer.class, importId)).isEqualTo(1);
    }

    @Test
    void aStoredRowThatNoLongerPassesValidationAbortsTheWholeCommit() throws Exception {
        String importId = preview(owner, HEADER + "Good Co,,,,,,\r\n" + "Corrupt Co,,ok@x.example,,,,\r\n");
        jdbc.update("update vendor_import set rows = jsonb_set(rows, '{1,email}', '\"not-an-email\"') "
                + "where id = ?::uuid", importId);

        commit(owner, importId).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Import has errors"));

        assertThat(vendorCount(owner)).isZero(); // nothing applied, not even the good row
        assertThat(importStatus(importId)).isEqualTo("PREVIEWED");
    }

    /** Runs the commit in another thread while a vendor edit transaction is held open; returns the commit status. */
    private int commitWhileEditIsOpen(String importId, String vendorId, String editSql) throws Exception {
        try (java.sql.Connection edit = jdbc.getDataSource().getConnection()) {
            edit.setAutoCommit(false);
            try (java.sql.PreparedStatement ps = edit.prepareStatement(editSql)) {
                ps.setString(1, vendorId);
                ps.executeUpdate(); // row lock held, transaction still open
            }
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                var client = owner.client().copy();
                Future<Integer> commit = pool.submit(() -> client.post(VENDORS + "/import/" + importId + "/commit",
                        null).andReturn().getResponse().getStatus());
                Thread.sleep(1_500);
                // The commit must be waiting for the vendor row lock, not racing past the uncommitted edit.
                assertThat(commit.isDone()).isFalse();
                edit.commit();
                return commit.get(30, java.util.concurrent.TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void aVendorEditHeldOpenDuringCommitIsNeverLost() throws Exception {
        String vendorId = createVendor(owner, "Locked Co", null, null, "Old Cat");
        String importId = preview(owner, HEADER + "Locked Co,,,,New Cat,,\r\n"); // UPDATE: category only

        int code = commitWhileEditIsOpen(importId, vendorId,
                "update vendor set contact_name = 'Edited Meanwhile' where id = ?::uuid");

        assertThat(code).isEqualTo(200);
        Map<String, Object> vendor = jdbc.queryForMap("select contact_name, category from vendor where id = ?::uuid",
                vendorId);
        assertThat(vendor).containsEntry("contact_name", "Edited Meanwhile").containsEntry("category", "New Cat");
    }

    @Test
    void aConcurrentEditThatChangesTheOutcomeMakesTheCommitConflict() throws Exception {
        String vendorId = createVendor(owner, "Raced Co", null, null, "Old Cat");
        String importId = preview(owner, HEADER + "Raced Co,,,,New Cat,,\r\n");

        // The edit already did what the file wants: the row is now UNCHANGED, not the previewed UPDATE.
        int code = commitWhileEditIsOpen(importId, vendorId,
                "update vendor set category = 'New Cat' where id = ?::uuid");

        assertThat(code).isEqualTo(409);
        assertThat(importStatus(importId)).isEqualTo("PREVIEWED");
    }
}
