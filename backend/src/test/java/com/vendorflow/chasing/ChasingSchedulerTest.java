package com.vendorflow.chasing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.chasing.application.ChasingService;
import com.vendorflow.document.domain.ReviewStatus;
import com.vendorflow.notification.EmailMessage;
import com.vendorflow.notification.NotificationKind;
import com.vendorflow.support.TestAccounts.Account;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/** The chasing engine over simulated time: gates, idempotency, cadence, episodes, exclusions, links, anti-spam. */
class ChasingSchedulerTest extends ChasingTestBase {

    private String chaseState(UUID vendor) throws Exception {
        return body(accounts.login(owner.email(), com.vendorflow.support.TestAccounts.PASSWORD).get("/api/v1/vendors/" + vendor + "/chasing")).get("status").asString();
    }

    private JsonNode portalInfo(String token) throws Exception {
        return json.readTree(vendorBrowser(token).perform(HttpMethod.GET, "/api/v1/portal/link", null, false)
                .andReturn().getResponse().getContentAsString());
    }

    private com.vendorflow.support.ApiClient vendorBrowser(String token) {
        return new com.vendorflow.support.ApiClient(mvc, json).remoteAddr("198.51.100." + (1 + (int) (Math.random() * 250)))
                .header("X-Portal-Token", token);
    }

    private int portalStatus(String token) throws Exception {
        return vendorBrowser(token).perform(HttpMethod.GET, "/api/v1/portal/link", null, false).andReturn()
                .getResponse().getStatus();
    }

    // ---- defaults and the happy path ----

    @Test
    void chasingIsOffUntilTheOrganizationTurnsItOn() throws Exception {
        UUID v = missingVendor(email("off"));
        assertThat(run().ran()).isFalse();
        assertThat(chaseRows(v)).isZero();
        assertThat(mailsTo(jdbc.queryForObject("select email from vendor where id = ?", String.class, v),
                NotificationKind.VENDOR_CHASE)).isEmpty();
        assertThat(chaseState(v)).isEqualTo("IDLE");
    }

    @Test
    void chasesAMissingVendorOnceWithAFreshLinkAndAnUnsubscribeLink() throws Exception {
        enableDefaults();
        String to = email("happy");
        UUID v = missingVendor(to);
        clockTo(NY, today(NY), LocalTime.of(10, 0));

        ChasingService.RunResult result = run();
        assertThat(result.ran()).isTrue();
        assertThat(result.chased()).isEqualTo(1);

        List<EmailMessage> mails = mailsTo(to, NotificationKind.VENDOR_CHASE);
        assertThat(mails).hasSize(1);
        EmailMessage mail = mails.get(0);
        assertThat(mail.textBody()).contains("W-9").contains("missing").contains("reminder 1 of 4")
                .contains("/portal#token=").contains("/portal/unsubscribe#token=");
        String token = group(UPLOAD_TOKEN, mail.textBody());
        JsonNode info = portalInfo(token);
        assertThat(info.get("documentTypes").size()).isEqualTo(1);
        assertThat(info.get("documentTypes").get(0).get("name").asString()).isEqualTo("W-9");

        assertThat(attempts(v)).isEqualTo(1);
        assertThat(chaseRows(v)).isEqualTo(1);
        assertThat(auditCount("vendor.chased")).isEqualTo(1);
        // The two raw secrets are gone from the stored payload once the row is SENT.
        String payload = jdbc.queryForObject("select payload::text from notification where recipient_email = ? "
                + "and kind = 'VENDOR_CHASE'", String.class, to);
        assertThat(payload).doesNotContain("\"token\"").doesNotContain("optOutToken").doesNotContain(token);
        // The audit metadata never carries a token either.
        assertThat(jdbc.queryForObject("select string_agg(metadata::text, '') from audit_event "
                + "where organization_id = ?::uuid and action = 'vendor.chased'", String.class, org))
                .doesNotContain(token);
        assertThat(chaseState(v)).isEqualTo("ACTIVE");
        JsonNode chases = body(owner.client().get("/api/v1/vendors/" + v + "/chases"));
        assertThat(chases.get("totalItems").asLong()).isEqualTo(1);
        assertThat(chases.get("items").get(0).get("attempt").asInt()).isEqualTo(1);
        assertThat(chases.get("items").get(0).get("linkStatus").asString()).isEqualTo("ACTIVE");
        assertThat(chases.get("items").get(0).get("emailStatus").asString()).isEqualTo("SENT");
        assertThat(chases.get("items").get(0).get("types").get(0).get("status").asString()).isEqualTo("MISSING");
    }

    // ---- time zones ----

    @Test
    void sendHourIsTheOrganizationsLocalHourNotUtc() throws Exception {
        // Two organizations, same instant, different zones: only the one whose LOCAL clock reached 09:00 is chased.
        owner.client().patch("/api/v1/organization", Map.of("timeZone", "Asia/Tokyo")).andExpect(status().isOk());
        other.client().patch("/api/v1/organization", Map.of("timeZone", "Pacific/Honolulu")).andExpect(status().isOk());
        enableDefaults();
        other.client().put("/api/v1/organization/chasing", settings(true, 7, 4, 30, 9, false)).andExpect(status().isOk());
        UUID tokyoVendor = missingVendor(email("tokyo"));
        UUID honoluluVendor = fx.vendor(otherOrg, "Honolulu vendor " + UUID.randomUUID());
        jdbc.update("update vendor set email = ? where id = ?", email("hnl"), honoluluVendor);
        fx.require(otherOrg, honoluluVendor, "W9");

        // 13:00 UTC = 22:00 Tokyo (past 09:00) and 03:00 Honolulu (before 09:00)
        LocalDate day = today("UTC").plusDays(1);
        clockTo("UTC", day, LocalTime.of(13, 0));
        chasing.runDue();
        assertThat(chaseRows(tokyoVendor)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from vendor_chase where vendor_id = ?", Integer.class,
                honoluluVendor)).isZero();
        assertThat(jdbc.queryForObject("select local_date from vendor_chase where vendor_id = ?", LocalDate.class,
                tokyoVendor)).isEqualTo(day);

        // 19:00 UTC = 09:00 Honolulu: now it is chased, and on ITS local date; Tokyo already did that day's chase
        clockTo("UTC", day, LocalTime.of(19, 0));
        chasing.runDue();
        assertThat(jdbc.queryForObject("select local_date from vendor_chase where vendor_id = ?", LocalDate.class,
                honoluluVendor)).isEqualTo(day);
        // Tokyo's local date changed at 15:00 UTC (00:00 JST) but its cadence (7 days) keeps it from a second chase.
        assertThat(chaseRows(tokyoVendor)).isEqualTo(1);
    }

    @Test
    void aTickAtOrAfterTheSendHourCatchesUpTheSameLocalDay() throws Exception {
        enable(7, 4, 30, 9, false);
        UUID v = missingVendor(email("late"));
        clockTo(NY, today(NY), LocalTime.of(8, 59));
        assertThat(run().ran()).isFalse();
        assertThat(chaseRows(v)).isZero();
        clockTo(NY, today(NY), LocalTime.of(17, 30));
        assertThat(run().chased()).isEqualTo(1);
    }

    // ---- idempotency ----

    @Test
    void twoTicksInTheSameLocalDayCreateOneEmail() throws Exception {
        enableDefaults();
        String to = email("twice");
        UUID v = missingVendor(to);
        clockTo(NY, today(NY), LocalTime.of(9, 5));
        assertThat(run().chased()).isEqualTo(1);
        assertThat(run().chased()).isZero();
        clockTo(NY, today(NY), LocalTime.of(18, 0));
        assertThat(run().chased()).isZero();
        assertThat(mailsTo(to, NotificationKind.VENDOR_CHASE)).hasSize(1);
        assertThat(chaseRows(v)).isEqualTo(1);
        assertThat(attempts(v)).isEqualTo(1);
    }

    @Test
    void concurrentTicksCreateOneEmail() throws Exception {
        enableDefaults();
        String to = email("race");
        UUID v = missingVendor(to);
        clockTo(NY, today(NY), LocalTime.of(10, 0));
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ChasingService.RunResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return chasing.runOrganization(orgId);
            }));
        }
        start.countDown();
        int chased = 0;
        for (Future<ChasingService.RunResult> f : futures) {
            chased += f.get().chased();
        }
        pool.shutdown();
        assertThat(chased).isEqualTo(1);
        assertThat(chaseRows(v)).isEqualTo(1);
        assertThat(attempts(v)).isEqualTo(1);
        assertThat(mailsTo(to, NotificationKind.VENDOR_CHASE)).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from vendor_upload_link where vendor_id = ?", Integer.class, v))
                .isEqualTo(1);
    }

    // ---- cadence, attempts, episodes ----

    @Test
    void cadenceMaxAttemptsExhaustionAndEpisodeReset() throws Exception {
        enable(3, 2, 30, 9, false);
        String to = email("episode");
        UUID v = missingVendor(to);
        List<LocalDate> chasedOn = new ArrayList<>();
        int sent = 0;
        for (int day = 0; day <= 9; day++) {
            if (day > 0) {
                nextDay(NY, LocalTime.of(9, 30));
            } else {
                clockTo(NY, today(NY), LocalTime.of(9, 30));
            }
            run();
            int now = mailsTo(to, NotificationKind.VENDOR_CHASE).size();
            if (now > sent) {
                chasedOn.add(today(NY));
                sent = now;
            }
        }
        LocalDate day0 = chasedOn.get(0);
        // attempt 1 on day 0, attempt 2 on day 3 (cadence), then exhausted: nothing on days 6 and 9
        assertThat(chasedOn).containsExactly(day0, day0.plusDays(3));
        assertThat(attempts(v)).isEqualTo(2);
        assertThat(chaseState(v)).isEqualTo("EXHAUSTED");
        // Staff were told ONCE (owner and admin are the verified staff), on the first due day after the last attempt.
        assertThat(mailsTo(owner.email(), NotificationKind.CHASING_STAFF_NOTICE)).hasSize(1);
        assertThat(mailsTo(admin.email(), NotificationKind.CHASING_STAFF_NOTICE)).hasSize(1);
        assertThat(mailsTo(owner.email(), NotificationKind.CHASING_STAFF_NOTICE).get(0).textBody())
                .contains("maximum number of times").contains("2 reminders sent");
        assertThat(mailsTo(member.email(), NotificationKind.CHASING_STAFF_NOTICE)).isEmpty();

        // The vendor becomes compliant: the next tick ends the episode.
        satisfyW9(v);
        nextDay(NY, LocalTime.of(9, 30));
        run();
        assertThat(attempts(v)).isZero();
        assertThat(jdbc.queryForObject("select exhausted_notified_at from vendor_chasing where vendor_id = ?",
                java.sql.Timestamp.class, v)).isNull();
        assertThat(chaseState(v)).isEqualTo("IDLE");

        // A new deficiency starts a new episode: attempt 1 again (cadence since the last chase is long over).
        removeDocuments(v);
        nextDay(NY, LocalTime.of(9, 30));
        assertThat(run().chased()).isEqualTo(1);
        assertThat(attempts(v)).isEqualTo(1);
        assertThat(mailsTo(to, NotificationKind.VENDOR_CHASE)).hasSize(3);
        assertThat(jdbc.queryForObject("select max(attempt) from vendor_chase where vendor_id = ? and local_date = ?",
                Integer.class, v, today(NY))).isEqualTo(1);
    }

    @Test
    void expiringDocumentsAreChasedOnlyInsideTheLeadWindow() throws Exception {
        enable(7, 4, 7, 9, false);
        String to = email("expiring");
        UUID v = vendor("Expiring " + UUID.randomUUID(), to);
        clockTo(NY, today(NY), LocalTime.of(10, 0));
        fx.requirement(org, v, "COI", ReviewStatus.APPROVED, today(NY).plusDays(20));
        assertThat(run().chased()).isZero(); // 20 days > leadDays 7

        enable(7, 4, 30, 9, false); // widen the window
        assertThat(run().chased()).isEqualTo(1);
        String body = mailsTo(to, NotificationKind.VENDOR_CHASE).get(0).textBody();
        assertThat(body).contains("Certificate of Insurance - expires on " + today(NY).plusDays(20));
    }

    @Test
    void anUploadWaitingForReviewIsNotChasedAndExpiredDocumentsAre() throws Exception {
        enableDefaults();
        clockTo(NY, today(NY), LocalTime.of(10, 0));
        UUID pending = vendor("Pending " + UUID.randomUUID(), email("pending"));
        fx.requirement(org, pending, "W9", ReviewStatus.PENDING, null);
        UUID expired = vendor("Expired " + UUID.randomUUID(), email("expired"));
        fx.requirement(org, expired, "COI", ReviewStatus.APPROVED, today(NY).minusDays(2));
        ChasingService.RunResult r = run();
        assertThat(r.chased()).isEqualTo(1);
        assertThat(chaseRows(pending)).isZero();
        assertThat(chaseRows(expired)).isEqualTo(1);
    }

    // ---- exclusions ----

    @Test
    void neverChasesInactivePausedEmaillessOrCompliantVendors() throws Exception {
        enableDefaults();
        clockTo(NY, today(NY), LocalTime.of(10, 0));
        UUID inactive = fx.vendor(org, "Inactive " + UUID.randomUUID(), "INACTIVE");
        jdbc.update("update vendor set email = ? where id = ?", email("inactive"), inactive);
        fx.require(org, inactive, "W9");
        UUID noEmail = missingVendor(null);
        UUID blankEmail = missingVendor("   ");
        UUID paused = missingVendor(email("paused"));
        owner.client().put("/api/v1/vendors/" + paused + "/chasing", Map.of("paused", true)).andExpect(status().isOk());
        UUID compliant = missingVendor(email("compliant"));
        satisfyW9(compliant);
        UUID included = missingVendor(email("included"));

        ChasingService.RunResult r = run();
        assertThat(r.chased()).isEqualTo(1);
        for (UUID excluded : List.of(inactive, noEmail, blankEmail, paused, compliant)) {
            assertThat(chaseRows(excluded)).as("vendor " + excluded).isZero();
        }
        assertThat(chaseRows(included)).isEqualTo(1);
        assertThat(chaseState(noEmail)).isEqualTo("NO_EMAIL");
        assertThat(chaseState(paused)).isEqualTo("PAUSED");
        assertThat(chaseState(inactive)).isEqualTo("IDLE");
    }

    @Test
    void aResumedVendorIsChasedAgain() throws Exception {
        enableDefaults();
        clockTo(NY, today(NY), LocalTime.of(10, 0));
        UUID v = missingVendor(email("resume"));
        owner.client().put("/api/v1/vendors/" + v + "/chasing", Map.of("paused", true)).andExpect(status().isOk());
        assertThat(run().chased()).isZero();
        owner.client().put("/api/v1/vendors/" + v + "/chasing", Map.of("paused", false)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(run().chased()).isEqualTo(1);
    }

    // ---- links ----

    @Test
    void eachChaseRevokesThePreviousLinkSoOnlyOneStaysLive() throws Exception {
        enable(3, 4, 30, 9, false);
        String to = email("links");
        UUID v = missingVendor(to);
        clockTo(NY, today(NY), LocalTime.of(9, 30));
        run();
        String token1 = group(UPLOAD_TOKEN, mailsTo(to, NotificationKind.VENDOR_CHASE).get(0).textBody());
        assertThat(portalStatus(token1)).isEqualTo(200);

        clockTo(NY, today(NY).plusDays(3), LocalTime.of(9, 30));
        assertThat(run().chased()).isEqualTo(1);
        List<EmailMessage> mails = mailsTo(to, NotificationKind.VENDOR_CHASE);
        assertThat(mails).hasSize(2);
        String token2 = group(UPLOAD_TOKEN, mails.get(1).textBody());
        assertThat(token2).isNotEqualTo(token1);
        assertThat(portalStatus(token1)).as("the old link was revoked").isEqualTo(404);
        assertThat(portalStatus(token2)).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from vendor_upload_link where vendor_id = ? "
                + "and revoked_at is null", Integer.class, v)).isEqualTo(1);
        // The vendor sees the activity with both link states.
        JsonNode items = body(accounts.login(owner.email(), com.vendorflow.support.TestAccounts.PASSWORD).get("/api/v1/vendors/" + v + "/chases")).get("items");
        assertThat(items.get(0).get("linkStatus").asString()).isEqualTo("ACTIVE");
        assertThat(items.get(1).get("linkStatus").asString()).isEqualTo("REVOKED");
        // Link lifetime = cadence + 7 days.
        long days = jdbc.queryForObject("select extract(epoch from (expires_at - created_at)) / 86400 "
                + "from vendor_upload_link where vendor_id = ? and revoked_at is null", Long.class, v);
        assertThat(days).isEqualTo(10);
    }

    // ---- anti-spam across channels ----

    @Test
    void aManualDocumentRequestTheSameDayBlocksTheChaseWithoutConsumingAnAttempt() throws Exception {
        enableDefaults();
        String to = email("manual");
        UUID v = missingVendor(to);
        clockTo(NY, today(NY), LocalTime.of(10, 0));
        String w9 = jdbc.queryForObject("select id::text from document_type where organization_id = ?::uuid and code = 'W9'",
                String.class, org);
        owner.client().post("/api/v1/vendors/" + v + "/document-requests", Map.of("documentTypeId", w9))
                .andExpect(status().isAccepted());

        ChasingService.RunResult r = run();
        assertThat(r.chased()).isZero();
        assertThat(r.skippedSameDay()).isEqualTo(1);
        assertThat(attempts(v)).isZero();
        assertThat(mailsTo(to, NotificationKind.VENDOR_CHASE)).isEmpty();
        assertThat(mailsTo(to, NotificationKind.DOCUMENT_REQUEST)).hasSize(1);

        nextDay(NY, LocalTime.of(10, 0));
        assertThat(run().chased()).isEqualTo(1);
        assertThat(attempts(v)).isEqualTo(1);
    }

    @Test
    void twoVendorsSharingAnAddressGetOneChaseEmailPerDay() throws Exception {
        enableDefaults();
        String shared = email("shared");
        UUID a = missingVendor(shared);
        UUID b = missingVendor(shared);
        clockTo(NY, today(NY), LocalTime.of(10, 0));
        ChasingService.RunResult r = run();
        assertThat(r.chased()).isEqualTo(1);
        assertThat(r.skippedSameDay()).isEqualTo(1);
        assertThat(mailsTo(shared, NotificationKind.VENDOR_CHASE)).hasSize(1);
        assertThat(chaseRows(a) + chaseRows(b)).isEqualTo(1);
        // The other one goes the next day.
        nextDay(NY, LocalTime.of(10, 0));
        assertThat(run().chased()).isEqualTo(1);
        assertThat(chaseRows(a) + chaseRows(b)).isEqualTo(2);
    }

    // ---- staff notices ----

    @Test
    void ccStaffSendsOneSummaryPerStaffMemberWithoutAnyLink() throws Exception {
        enable(7, 4, 30, 9, true);
        UUID v1 = missingVendor(email("cc1"));
        UUID v2 = missingVendor(email("cc2"));
        clockTo(NY, today(NY), LocalTime.of(10, 0));
        assertThat(run().chased()).isEqualTo(2);
        for (Account staff : List.of(owner, admin)) {
            List<EmailMessage> notices = mailsTo(staff.email(), NotificationKind.CHASING_STAFF_NOTICE);
            assertThat(notices).hasSize(1);
            assertThat(notices.get(0).textBody()).contains("sent these vendors a reminder").doesNotContain("token=")
                    .doesNotContain("/portal");
        }
        assertThat(mailsTo(member.email(), NotificationKind.CHASING_STAFF_NOTICE)).isEmpty();
        assertThat(mailsTo(viewer.email(), NotificationKind.CHASING_STAFF_NOTICE)).isEmpty();
        assertThat(v1).isNotEqualTo(v2);
    }

    @Test
    void withoutCcStaffNoSummaryIsSent() throws Exception {
        enableDefaults();
        missingVendor(email("quiet"));
        clockTo(NY, today(NY), LocalTime.of(10, 0));
        assertThat(run().chased()).isEqualTo(1);
        assertThat(mailsTo(owner.email(), NotificationKind.CHASING_STAFF_NOTICE)).isEmpty();
    }
}
