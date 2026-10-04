package com.vendorflow.vendor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.mock.web.MockHttpServletResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** POST /vendors/import/preview: file checks, header and row validation, action resolution, storage and limits. */
class VendorImportPreviewTest extends VendorCsvTestBase {

    // ---- happy path and action resolution ----

    @Test
    void previewClassifiesRowsAndStoresThePreviewWithoutTouchingVendors() throws Exception {
        createVendor(owner, "Existing Same", "Sam", "sam@x.example", "Cat");
        createVendor(owner, "Existing Changes", "Old Contact", "old@x.example", "Cat");
        String csv = HEADER
                + "New Vendor,Nina,nina@x.example,555-0101,Plumbing,,ACTIVE\r\n"
                + "Existing Same,Sam,sam@x.example,,Cat,,\r\n"
                + "Existing Changes,New Contact,,,,,\r\n"
                + ",Nameless,,,,,\r\n";

        JsonNode preview = previewOk(owner, csv);

        assertThat(preview.get("importId").asString()).isNotBlank();
        assertThat(preview.get("expiresAt").asString()).isNotBlank();
        assertThat(preview.get("summary").toString())
                .isEqualTo("{\"total\":4,\"create\":1,\"update\":1,\"unchanged\":1,\"error\":1}");
        assertThat(row(preview, 1).get("action").asString()).isEqualTo("CREATE");
        assertThat(row(preview, 1).get("companyName").asString()).isEqualTo("New Vendor");
        assertThat(row(preview, 2).get("action").asString()).isEqualTo("UNCHANGED");
        assertThat(row(preview, 3).get("action").asString()).isEqualTo("UPDATE");
        assertThat(row(preview, 3).get("changes").toString()).isEqualTo("[\"contact_name\"]");
        assertThat(row(preview, 4).get("action").asString()).isEqualTo("ERROR");

        // nothing was applied
        assertThat(jdbc.queryForObject("select count(*) from vendor where organization_id = ?::uuid", Integer.class,
                owner.organizationId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                select count(*) from vendor_import
                where organization_id = ?::uuid and status = 'PREVIEWED' and id = ?::uuid""", Integer.class,
                owner.organizationId(), preview.get("importId").asString())).isEqualTo(1);
    }

    @Test
    void emptyCellsNeverEraseExistingDataAndStatusIsAppliedOnlyWhenPresent() throws Exception {
        createVendor(owner, "Keep Me", "Contact", "keep@x.example", "Cat");
        createVendor(owner, "Rename Me", null, null, null);
        createVendor(owner, "Deactivate Me", null, null, null);

        JsonNode preview = previewOk(owner, HEADER
                + "Keep Me,,,,,,\r\n"                    // all empty: nothing to change
                + "rename me,,,,,,\r\n"                  // only the letter case differs: still a change
                + "Deactivate Me,,,,,,INACTIVE\r\n");

        assertThat(row(preview, 1).get("action").asString()).isEqualTo("UNCHANGED");
        assertThat(row(preview, 2).get("action").asString()).isEqualTo("UPDATE");
        assertThat(row(preview, 2).get("changes").toString()).isEqualTo("[\"company_name\"]");
        assertThat(row(preview, 3).get("changes").toString()).isEqualTo("[\"status\"]");
    }

    @Test
    void matchingIsCaseInsensitiveAndTrimmedAndStatusIsCaseInsensitive() throws Exception {
        createVendor(owner, "Acme Co", null, null, null);
        JsonNode preview = previewOk(owner, HEADER + "  ACME CO  ,,,,,,active\r\n");
        assertThat(row(preview, 1).get("action").asString()).isEqualTo("UPDATE"); // the name case differs
        assertThat(row(preview, 1).get("changes").toString()).isEqualTo("[\"company_name\"]");
    }

    @Test
    void headerNamesAreCaseInsensitiveTrimmedOrderFreeAndReadOnlyColumnsAreIgnored() throws Exception {
        JsonNode preview = previewOk(owner, " Category , COMPANY_NAME ,compliance_status,missing,next_expiration\r\n"
                + "Plumbing,Reordered Co,NON_COMPLIANT,3,2030-01-01\r\n");
        assertThat(preview.get("summary").get("create").asInt()).isEqualTo(1);
        assertThat(preview.get("summary").get("error").asInt()).isZero();
    }

    @Test
    void bomAndNoBomAndCrlfAndLfAreAllAccepted() throws Exception {
        String lf = "company_name,category\nLf Co,A\nLf Two,B\n";
        String bom = "\uFEFFcompany_name,category\r\nBom Co,A\r\n";
        assertThat(previewOk(owner, lf).get("summary").get("create").asInt()).isEqualTo(2);
        assertThat(previewOk(owner, bom).get("summary").get("create").asInt()).isEqualTo(1);
        assertThat(previewOk(owner, "company_name\r\nNo Newline At End").get("summary").get("create").asInt())
                .isEqualTo(1);
        // a real BOM as bytes (not the char in a Java string, which is encoded the same way: assert both paths)
        byte[] withBomBytes = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = "company_name\r\nBytes Bom Co\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[withBomBytes.length + body.length];
        System.arraycopy(withBomBytes, 0, all, 0, 3);
        System.arraycopy(body, 0, all, 3, body.length);
        assertThat(previewOk(owner, all).get("summary").get("create").asInt()).isEqualTo(1);
    }

    @Test
    void quotedFieldsMayContainCommasQuotesAndLineBreaks() throws Exception {
        JsonNode preview = previewOk(owner, HEADER
                + "\"Smith, Jones \"\"Sons\"\"\",,,,,\"first line\r\nsecond line, with comma\",\r\n");
        assertThat(preview.get("summary").get("error").asInt()).isZero();
        assertThat(row(preview, 1).get("companyName").asString()).isEqualTo("Smith, Jones \"Sons\"");
        // the stored notes keep the line break (normalized to LF)
        String notes = jdbc.queryForObject("""
                select rows -> 0 ->> 'notes' from vendor_import where id = ?::uuid""", String.class,
                preview.get("importId").asString());
        assertThat(notes).isEqualTo("first line\nsecond line, with comma");
    }

    @Test
    void blankLinesAreSkippedButStillNumberedLikeInASpreadsheet() throws Exception {
        JsonNode preview = previewOk(owner, "company_name,email\r\nFirst,\r\n\r\n,,\r\nFourth,\r\n");
        assertThat(preview.get("summary").get("total").asInt()).isEqualTo(2);
        assertThat(row(preview, 1).get("companyName").asString()).isEqualTo("First");
        assertThat(row(preview, 4).get("companyName").asString()).isEqualTo("Fourth");
    }

    // ---- row validation: same rules as the vendor API ----

    @Test
    void everyFieldRuleOfTheVendorApiIsReportedWithRowNumberAndColumn() throws Exception {
        String csv = HEADER
                + ",x,,,,,\r\n"                                                     // 1 missing company
                + "A".repeat(201) + ",,,,,,\r\n"                                    // 2 company too long
                + "Bad Email Co,,not-an-email,,,,\r\n"                              // 3 e-mail
                + "Bad Phone Co,,,call me maybe,,,\r\n"                             // 4 phone
                + "Long Contact Co," + "C".repeat(121) + ",,,,,\r\n"                // 5 contact too long
                + "Long Category Co,,,," + "K".repeat(61) + ",,\r\n"                // 6 category too long
                + "Long Notes Co,,,,," + "N".repeat(5001) + ",\r\n"                 // 7 notes too long
                + "Spoof \u202Eevil,,,,,,\r\n"                                      // 8 invisible formatting char
                + "Bad Status Co,,,,,,MAYBE\r\n"                                    // 9 status
                + "Long Email Co,,"+ "e".repeat(250) + "@x.example,,,,\r\n";        // 10 e-mail too long

        JsonNode preview = previewOk(owner, csv);

        assertThat(preview.get("summary").get("error").asInt()).isEqualTo(10);
        assertThat(hasError(row(preview, 1), "company_name")).isTrue();
        assertThat(hasError(row(preview, 2), "company_name")).isTrue();
        assertThat(hasError(row(preview, 3), "email")).isTrue();
        assertThat(hasError(row(preview, 4), "phone")).isTrue();
        assertThat(hasError(row(preview, 5), "contact_name")).isTrue();
        assertThat(hasError(row(preview, 6), "category")).isTrue();
        assertThat(hasError(row(preview, 7), "notes")).isTrue();
        assertThat(hasError(row(preview, 8), "company_name")).isTrue();
        assertThat(hasError(row(preview, 9), "status")).isTrue();
        assertThat(hasError(row(preview, 10), "email")).isTrue();
        for (JsonNode r : preview.get("rows")) {
            assertThat(r.get("action").asString()).isEqualTo("ERROR");
            assertThat(r.get("errors").get(0).get("message").asString()).isNotBlank();
        }
    }

    @Test
    void notesMayContainLineBreaksButNotOtherControlCharacters() throws Exception {
        JsonNode preview = previewOk(owner, HEADER
                + "Fine Notes Co,,,,,\"a\nb\tc\",\r\n"
                + "Bad Notes Co,,,,,\"a\u0007b\",\r\n");
        assertThat(row(preview, 1).get("action").asString()).isEqualTo("CREATE");
        assertThat(hasError(row(preview, 2), "notes")).isTrue();
    }

    @Test
    void sameNameTwiceInTheFileIsAnErrorOnEveryDuplicateRow() throws Exception {
        JsonNode preview = previewOk(owner, HEADER
                + "Twin Co,,,,,,\r\n"
                + "Unique Co,,,,,,\r\n"
                + "  twin co ,,,,,,\r\n"
                + "TWIN CO,,,,,,\r\n");
        for (int n : List.of(1, 3, 4)) {
            assertThat(row(preview, n).get("action").asString()).isEqualTo("ERROR");
            assertThat(hasError(row(preview, n), "company_name")).isTrue();
        }
        assertThat(row(preview, 2).get("action").asString()).isEqualTo("CREATE");
    }

    @Test
    void dataOutsideTheNamedColumnsIsARowError() throws Exception {
        JsonNode preview = previewOk(owner, "company_name,email\r\nExtra Cell Co,a@x.example,surprise\r\n");
        assertThat(hasError(row(preview, 1), "row")).isTrue();
    }

    // ---- header and file level problems ----

    @Test
    void unknownColumnsAreReportedOnTheFirstRow() throws Exception {
        JsonNode preview = previewOk(owner, "company_name,favorite_color,Shoe Size\r\nA Co,red,9\r\nB Co,blue,10\r\n");
        assertThat(row(preview, 1).get("action").asString()).isEqualTo("ERROR");
        assertThat(hasError(row(preview, 1), "favorite_color")).isTrue();
        assertThat(hasError(row(preview, 1), "shoe size")).isTrue();
        assertThat(row(preview, 2).get("action").asString()).isEqualTo("CREATE");
    }

    @Test
    void missingRequiredColumnIsRejected() throws Exception {
        upload(owner, "email,phone\r\na@x.example,555-0100\r\n").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("company_name"));
    }

    @Test
    void duplicateHeaderColumnsAreRejected() throws Exception {
        upload(owner, "company_name,email,Email\r\nA Co,a@x.example,b@x.example\r\n")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("email"));
    }

    @Test
    void emptyFileAndHeaderOnlyFileAreRejected() throws Exception {
        upload(owner, "").andExpect(status().isBadRequest());
        upload(owner, "company_name,email\r\n").andExpect(status().isBadRequest());
        upload(owner, "\r\n\r\n").andExpect(status().isBadRequest());
        owner.client().postMultipart(PREVIEW, null, Map.of()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("file"));
    }

    @Test
    void invalidUtf8IsRejectedWithAClearMessage() throws Exception {
        byte[] head = "company_name\r\nCaf".getBytes(StandardCharsets.UTF_8);
        byte[] bad = new byte[head.length + 3];
        System.arraycopy(head, 0, bad, 0, head.length);
        bad[head.length] = (byte) 0xE9; // latin-1 "e acute": a lone continuation-less lead byte, invalid UTF-8
        bad[head.length + 1] = (byte) 'x';
        bad[head.length + 2] = (byte) '\n';
        upload(owner, bad, "vendors.csv").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("UTF-8")));
        // valid multi-byte UTF-8 is fine
        assertThat(previewOk(owner, "company_name\r\nCaf\u00E9 \u4E2D\u6587 \uD83D\uDE80\r\n").get("summary")
                .get("create").asInt()).isEqualTo(1);
    }

    @Test
    void unterminatedQuoteIsAnInvalidFileNotAServerError() throws Exception {
        upload(owner, "company_name,notes\r\nA Co,\"never closed\r\nB Co,x\r\n").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid CSV file"));
    }

    @Test
    void filesOverOneMegabyteAreRejectedWith413() throws Exception {
        StringBuilder sb = new StringBuilder("company_name,notes\r\n");
        String line = "Big Co,xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\r\n";
        while (sb.length() <= 1024 * 1024) {
            sb.append(line);
        }
        upload(owner, sb.toString()).andExpect(status().isPayloadTooLarge());
    }

    @Test
    void moreThanTwoThousandRowsIsRejectedWith422ButExactlyTwoThousandIsAccepted() throws Exception {
        StringBuilder ok = new StringBuilder("company_name\r\n");
        for (int i = 1; i <= 2000; i++) {
            ok.append("Vendor ").append(i).append("\r\n");
        }
        assertThat(previewOk(owner, ok.toString()).get("summary").get("total").asInt()).isEqualTo(2000);

        upload(owner, ok + "Vendor 2001\r\n").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Too many rows"));
    }

    @Test
    void onlyCsvFilesAreAccepted() throws Exception {
        byte[] content = (HEADER + "Ext Co,,,,,,\r\n").getBytes(StandardCharsets.UTF_8);
        for (String name : List.of("vendors.txt", "vendors.csv.exe", "vendors", "vendors.xlsx", "..\\..\\x.php")) {
            upload(owner, content, name).andExpect(status().isUnsupportedMediaType());
        }
        upload(owner, content, "VENDORS.CSV").andExpect(status().isOk());
        upload(owner, content, "C:\\Users\\me\\Desktop\\my vendors.csv").andExpect(status().isOk());
    }

    // ---- authorization, tenant isolation, rate limit, storage ----

    @Test
    void memberAndViewerCannotPreviewAdminCan() throws Exception {
        String csv = HEADER + "Role Co,,,,,,\r\n";
        upload(member, csv).andExpect(status().isForbidden());
        upload(viewer, csv).andExpect(status().isForbidden());
        upload(admin, csv).andExpect(status().isOk());
        upload(owner, csv).andExpect(status().isOk());
        accounts.newClient().postMultipart(PREVIEW, new org.springframework.mock.web.MockMultipartFile("file",
                "v.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)), Map.of())
                .andExpect(status().isUnauthorized());
    }

    @Test
    void previewOfAnotherOrganizationNeverSeesOrChangesMyVendors() throws Exception {
        createVendor(owner, "Shared Name Co", "Owner Contact", "o@x.example", "Cat");

        JsonNode preview = previewOk(other, HEADER + "Shared Name Co,Other Contact,,,,,\r\n");

        assertThat(row(preview, 1).get("action").asString()).isEqualTo("CREATE"); // not an UPDATE of A's vendor
        assertThat(jdbc.queryForObject("select contact_name from vendor where organization_id = ?::uuid",
                String.class, owner.organizationId())).isEqualTo("Owner Contact");
    }

    @Test
    void previewsAreLimitedToTenPerMinutePerUser() throws Exception {
        String csv = HEADER + "Rate Co,,,,,,\r\n";
        for (int i = 0; i < 10; i++) {
            upload(owner, csv).andExpect(status().isOk());
        }
        upload(owner, csv).andExpect(status().isTooManyRequests()).andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().exists("Retry-After"));
        upload(admin, csv).andExpect(status().isOk()); // the budget is per user
    }

    @Test
    void expiredPreviewsAreDeletedWhenANewPreviewIsCreated() throws Exception {
        String csv = HEADER + "Purge Co,,,,,,\r\n";
        String oldId = previewOk(owner, csv).get("importId").asString();
        clock.advance(Duration.ofHours(30)); // expired for 29 h, past the 24 h grace
        String freshId = previewOk(owner, csv).get("importId").asString();

        List<String> ids = new ArrayList<>(jdbc.queryForList("select id::text from vendor_import where "
                + "organization_id = ?::uuid", String.class, owner.organizationId()));
        assertThat(ids).contains(freshId).doesNotContain(oldId);
    }

    @Test
    void errorResponsesNeverEchoFileContents() throws Exception {
        String secret = "TOPSECRET-VENDOR-DATA";
        String body = upload(owner, "company_name\r\n" + secret + ",\"unterminated\r\n").andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(secret);
    }

    // ---- hostile files: bounded work, bounded storage, bounded responses (security fix M1) ----

    @Test
    void aMegabyteOfUniqueHeaderNamesIsRejectedWithABoundedResponse() throws Exception {
        StringBuilder header = new StringBuilder();
        for (int i = 0; header.length() < 1_000_000; i++) {
            header.append("col").append(i).append(',');
        }
        String body = upload(owner, header + "company_name\r\nX Co\r\n").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid CSV file")).andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("at most 30")))
                .andReturn().getResponse().getContentAsString();
        assertThat(body.length()).isLessThan(2_000);
    }

    @Test
    void threeHundredColumnsAreRejected() throws Exception {
        StringBuilder header = new StringBuilder("company_name");
        for (int i = 0; i < 299; i++) {
            header.append(",extra").append(i);
        }
        upload(owner, header + "\r\nX Co\r\n").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid CSV file"));
    }

    @Test
    void unknownColumnErrorsAreCappedAtTwentyPlusASummaryAndNamesAreTruncated() throws Exception {
        StringBuilder header = new StringBuilder("company_name"); // 1 + 25 unknown = 26 columns (limit 30)
        for (int i = 0; i < 24; i++) {
            header.append(",extra").append(i);
        }
        header.append(',').append("n".repeat(200));
        JsonNode preview = previewOk(owner, header + "\r\nX Co\r\n");

        JsonNode errors = row(preview, 1).get("errors");
        int unknown = 0;
        boolean summary = false;
        for (JsonNode e : errors) {
            if ("Unknown column".equals(e.get("message").asString())) {
                unknown++;
                assertThat(e.get("field").asString().length()).isLessThanOrEqualTo(50);
            } else if ("and 5 more unknown columns".equals(e.get("message").asString())) {
                summary = true;
            }
        }
        assertThat(unknown).isEqualTo(20);
        assertThat(summary).isTrue();
        assertThat(errors.size()).isEqualTo(21);
    }

    @Test
    void aOneMegabyteCellIsAnErrorRowAndNeitherStoredNorEchoed() throws Exception {
        String csv = "company_name,contact_name\r\n" + "a".repeat(900_000) + ",Contact\r\n";
        MockHttpServletResponse response = upload(owner, csv).andExpect(status().isOk()).andReturn().getResponse();
        JsonNode preview = json.readTree(response.getContentAsString());

        assertThat(row(preview, 1).get("action").asString()).isEqualTo("ERROR");
        assertThat(row(preview, 1).get("errors").toString()).contains("Value too long");
        assertThat(response.getContentAsString().length()).isLessThan(5_000);
        Integer stored = jdbc.queryForObject("select octet_length(rows::text) from vendor_import where id = ?::uuid",
                Integer.class, preview.get("importId").asString());
        assertThat(stored).isLessThan(5_000);
    }

    @Test
    void errorRowsStoreValuesTruncatedToTheirColumnMaximum() throws Exception {
        String csv = HEADER + "Notes Co,,,,," + "n".repeat(9_000) + ",\r\n"; // over 5,000 (error), under the cell cap
        JsonNode preview = previewOk(owner, csv);

        assertThat(row(preview, 1).get("action").asString()).isEqualTo("ERROR");
        Integer notesLength = jdbc.queryForObject("select length(rows->0->>'notes') from vendor_import "
                + "where id = ?::uuid", Integer.class, preview.get("importId").asString());
        assertThat(notesLength).isEqualTo(5_000);
    }
}
