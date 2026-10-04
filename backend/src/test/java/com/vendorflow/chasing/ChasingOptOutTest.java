package com.vendorflow.chasing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.notification.NotificationKind;
import com.vendorflow.support.ApiClient;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/** The public vendor unsubscribe: GET never changes state, POST pauses once, bad tokens look identical. */
class ChasingOptOutTest extends ChasingTestBase {

    static final String PATH = "/api/v1/portal/chasing/opt-out";

    String to;
    UUID vendor;
    String optOutToken;
    String uploadToken;

    @BeforeEach
    void chaseOnce() throws Exception {
        enableDefaults();
        to = email("optout");
        vendor = missingVendor(to);
        clockTo(NY, today(NY), LocalTime.of(10, 0));
        assertThat(run().chased()).isEqualTo(1);
        String text = mailsTo(to, NotificationKind.VENDOR_CHASE).get(0).textBody();
        optOutToken = group(OPT_OUT_TOKEN, text);
        uploadToken = group(UPLOAD_TOKEN, text);
    }

    private ApiClient browser(String token) {
        ApiClient c = new ApiClient(mvc, json).remoteAddr("198.51.100." + (1 + (int) (Math.random() * 250)));
        return token == null ? c : c.header("X-Portal-Token", token);
    }

    private ResultActions get(String token) throws Exception {
        return browser(token).perform(HttpMethod.GET, PATH, null, false);
    }

    private ResultActions post(String token) throws Exception {
        // No cookie and no CSRF header at all: the vendor has no session.
        return browser(token).perform(HttpMethod.POST, PATH, null, false);
    }

    private boolean paused() {
        return jdbc.queryForObject("select paused from vendor_chasing where vendor_id = ?", Boolean.class, vendor);
    }

    @Test
    void getShowsWhoAndWhatButNeverChangesState() throws Exception {
        for (int i = 0; i < 3; i++) {
            get(optOutToken).andExpect(status().isOk()).andExpect(jsonPath("$.optedOut").value(false))
                    .andExpect(jsonPath("$.organizationName").exists()).andExpect(jsonPath("$.vendorName").exists())
                    .andExpect(header().string("Cache-Control", containsString("no-store")));
        }
        assertThat(paused()).isFalse();
        assertThat(auditCount("vendor.chasing.opted_out")).isZero();
        assertThat(mailsTo(owner.email(), NotificationKind.CHASING_STAFF_NOTICE)).isEmpty();
        // The response carries nothing beyond names and the flag (no ids, no address).
        String raw = get(optOutToken).andReturn().getResponse().getContentAsString();
        assertThat(raw).doesNotContain(vendor.toString()).doesNotContain(org).doesNotContain("@");
    }

    @Test
    void postPausesChasingNotifiesStaffOnceAndIsIdempotent() throws Exception {
        post(optOutToken).andExpect(status().isOk()).andExpect(jsonPath("$.optedOut").value(true));
        assertThat(paused()).isTrue();
        assertThat(jdbc.queryForObject("select paused_reason from vendor_chasing where vendor_id = ?", String.class,
                vendor)).isEqualTo("OPT_OUT");
        assertThat(auditCount("vendor.chasing.opted_out")).isEqualTo(1);
        for (var staff : List.of(owner, admin)) {
            List<com.vendorflow.notification.EmailMessage> mails = mailsTo(staff.email(), NotificationKind.CHASING_STAFF_NOTICE);
            assertThat(mails).hasSize(1);
            assertThat(mails.get(0).textBody()).contains("used the unsubscribe link").doesNotContain("token=");
        }
        // Repeating (and GET afterwards) changes nothing and notifies nobody again.
        post(optOutToken).andExpect(status().isOk()).andExpect(jsonPath("$.optedOut").value(true));
        get(optOutToken).andExpect(status().isOk()).andExpect(jsonPath("$.optedOut").value(true));
        assertThat(auditCount("vendor.chasing.opted_out")).isEqualTo(1);
        assertThat(mailsTo(owner.email(), NotificationKind.CHASING_STAFF_NOTICE)).hasSize(1);

        // Staff see it, and no further chase is ever sent.
        owner.client().get("/api/v1/vendors/" + vendor + "/chasing").andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.pausedReason").value("OPT_OUT"));
        clockTo(NY, today(NY).plusDays(30), LocalTime.of(10, 0));
        assertThat(run().chased()).isZero();
        assertThat(mailsTo(to, NotificationKind.VENDOR_CHASE)).hasSize(1);
    }

    @Test
    void staffCannotResumeAVendorThatOptedOut() throws Exception {
        post(optOutToken).andExpect(status().isOk());
        owner.client().put("/api/v1/vendors/" + vendor + "/chasing", Map.of("paused", false))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://vendorflow.app/problems/vendor-opted-out"));
        assertThat(paused()).isTrue();
        // Pausing (already paused) is a harmless no-op that must not downgrade the reason.
        owner.client().put("/api/v1/vendors/" + vendor + "/chasing", Map.of("paused", true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.pausedReason").value("OPT_OUT"));
    }

    @Test
    void optingOutOverridesAManualPause() throws Exception {
        owner.client().put("/api/v1/vendors/" + vendor + "/chasing", Map.of("paused", true)).andExpect(status().isOk());
        post(optOutToken).andExpect(status().isOk());
        owner.client().get("/api/v1/vendors/" + vendor + "/chasing").andExpect(jsonPath("$.pausedReason").value("OPT_OUT"));
        assertThat(auditCount("vendor.chasing.opted_out")).isEqualTo(1);
    }

    @Test
    void anOlderEmailsLinkStillWorksAfterNewerChases() throws Exception {
        clockTo(NY, today(NY).plusDays(7), LocalTime.of(10, 0));
        assertThat(run().chased()).isEqualTo(1);
        post(optOutToken).andExpect(status().isOk()).andExpect(jsonPath("$.optedOut").value(true));
        assertThat(paused()).isTrue();
    }

    @Test
    void everyBadTokenGetsTheSameFourOhFour() throws Exception {
        String random = "A".repeat(43);
        // A real portal UPLOAD token is not an opt-out token (separate namespaces), and an expired one is invalid too.
        List<String> tokens = new ArrayList<>(java.util.Arrays.asList(null, "", "short", random, "bad token!".repeat(5),
                uploadToken, "x".repeat(500)));
        List<JsonNode> responses = new ArrayList<>();
        for (String token : tokens) {
            for (boolean isPost : new boolean[] {false, true}) {
                var result = (isPost ? post(token) : get(token)).andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.type").value("https://vendorflow.app/problems/chasing-opt-out-invalid"));
                responses.add(json.readTree(result.andReturn().getResponse().getContentAsString()));
            }
        }
        jdbc.update("update vendor_chase set opt_out_expires_at = now() - interval '400 days' where vendor_id = ?", vendor);
        for (var r : List.of(get(optOutToken), post(optOutToken))) {
            responses.add(json.readTree(r.andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString()));
        }
        JsonNode first = responses.get(0);
        for (JsonNode r : responses) {
            assertThat(r.get("type")).isEqualTo(first.get("type"));
            assertThat(r.get("title")).isEqualTo(first.get("title"));
            assertThat(r.get("detail")).isEqualTo(first.get("detail"));
            assertThat(r.get("status")).isEqualTo(first.get("status"));
        }
        assertThat(paused()).as("an invalid token never changes anything").isFalse();
        assertThat(auditCount("vendor.chasing.opted_out")).isZero();
    }

    @Test
    void aStaffSessionCookieIsIgnoredAndNoCsrfTokenIsNeeded() throws Exception {
        // The endpoint reads no cookie: a logged-in staff browser without the token is still just "invalid".
        owner.client().perform(HttpMethod.POST, PATH, null, true).andExpect(status().isNotFound());
        owner.client().perform(HttpMethod.GET, PATH, null, true).andExpect(status().isNotFound());
    }

    @Test
    void optingOutWorksForAnInactiveVendor() throws Exception {
        jdbc.update("update vendor set status = 'INACTIVE' where id = ?", vendor);
        post(optOutToken).andExpect(status().isOk()).andExpect(jsonPath("$.optedOut").value(true));
        assertThat(paused()).isTrue();
    }
}
