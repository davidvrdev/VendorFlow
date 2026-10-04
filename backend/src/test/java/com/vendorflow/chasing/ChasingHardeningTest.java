package com.vendorflow.chasing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.vendorflow.chasing.application.ChasingService;
import com.vendorflow.chasing.infrastructure.ChasingStore;
import com.vendorflow.notification.EmailMessage;
import com.vendorflow.notification.EmailSuppressionService;
import com.vendorflow.notification.NotificationKind;
import com.vendorflow.support.ApiClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Security-review hardening of automated chasing: lock design (M1), per-vendor isolation (M2), global suppression and
 * caps (M3), CAN-SPAM footer / one-click unsubscribe (M4) and the vendor pause/opt-out races (L1, L2).
 */
class ChasingHardeningTest extends ChasingTestBase {

    static final String ONE_CLICK = "/api/v1/portal/chasing/one-click/";

    @Autowired PlatformTransactionManager txManager;
    @Autowired MeterRegistry meters;
    @Autowired ChasingStore chasingStore;
    @Autowired EmailSuppressionService suppression;

    private Object target;
    private ListAppender<ILoggingEvent> logs;
    private Logger serviceLogger;

    @BeforeEach
    void captureLogs() {
        target = AopProxyUtils.getSingletonTarget(chasing) != null ? AopProxyUtils.getSingletonTarget(chasing) : chasing;
        serviceLogger = (Logger) LoggerFactory.getLogger(ChasingService.class);
        logs = new ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void restore() {
        serviceLogger.detachAppender(logs);
        ReflectionTestUtils.setField(target, "dailyCap", 200);
        ReflectionTestUtils.setField(target, "postalAddress", "VendorFlow Test, 1 Test Street, Springfield, USA");
        jdbc.execute("drop trigger if exists poison_vendor_chase on vendor_chase");
        jdbc.execute("drop function if exists poison_vendor_chase()");
    }

    private ApiClient browser() {
        return new ApiClient(mvc, json).remoteAddr("198.51.100." + (1 + (int) (Math.random() * 250)));
    }

    private String chaseAndGetOptOutToken(String to) throws Exception {
        clockTo(NY, today(NY), LocalTime.of(10, 0));
        assertThat(run().chased()).isEqualTo(1);
        return group(OPT_OUT_TOKEN, mailsTo(to, NotificationKind.VENDOR_CHASE).get(0).textBody());
    }

    private String logText() {
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent e : logs.list) {
            sb.append(e.getLevel()).append(' ').append(e.getFormattedMessage()).append('\n');
        }
        return sb.toString();
    }

    // ---- M1: the tick must not block the tenant's child-table inserts ----

    @Test
    void aRunningTickIsExclusiveButBlocksNoInsertOfTheSameOrganization() throws Exception {
        enableDefaults();
        String to = email("m1");
        UUID v = missingVendor(to);
        String optOutToken = null;
        TransactionTemplate tt = new TransactionTemplate(txManager);
        // Hold exactly what a running tick holds: the org-level advisory lock, inside an open transaction.
        tt.executeWithoutResult(status -> {
            Boolean got = jdbc.queryForObject("select pg_try_advisory_xact_lock(hashtextextended(?, 0))", Boolean.class,
                    "chasing-org:" + orgId);
            assertThat(got).isTrue();
            try {
                // Another tick of the same organization skips instead of waiting or running twice.
                ChasingService.RunResult other = CompletableFuture.supplyAsync(this::run).get(10, TimeUnit.SECONDS);
                assertThat(other.ran()).isFalse();
                // Child-table inserts (FOR KEY SHARE on organization) and the public opt-out path are not blocked.
                UUID inserted = CompletableFuture.supplyAsync(() -> vendor("Concurrent " + UUID.randomUUID(), null))
                        .get(5, TimeUnit.SECONDS);
                assertThat(inserted).isNotNull();
                CompletableFuture.runAsync(() -> jdbc.update("""
                        insert into vendor_chasing (vendor_id, organization_id, paused, paused_reason, paused_at, updated_at)
                        values (?, ?::uuid, true, 'OPT_OUT', now(), now())""", v, org)).get(5, TimeUnit.SECONDS);
                // A parent-row UPDATE by the organization itself is not blocked either.
                CompletableFuture.runAsync(() -> jdbc.update("update organization set name = name where id = ?::uuid", org))
                        .get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new AssertionError("a tick must not block organization child inserts: " + e, e);
            }
        });
        // Lock released at commit: the next run proceeds (the vendor above was opted out, so nothing is sent).
        assertThat(run().ran()).isTrue();
    }

    // ---- M2: one poison vendor costs only itself ----

    @Test
    void aPoisonVendorIsLoggedCountedAndSkippedWithoutRollingBackTheOthers() throws Exception {
        enableDefaults();
        List<UUID> good = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            good.add(missingVendor(email("good")));
        }
        UUID poison = missingVendor(email("poison"));
        jdbc.execute("""
                create function poison_vendor_chase() returns trigger language plpgsql as $$
                begin
                  if new.vendor_id = '%s'::uuid then raise exception 'boom for poison vendor'; end if;
                  return new;
                end $$""".formatted(poison));
        jdbc.execute("create trigger poison_vendor_chase before insert on vendor_chase "
                + "for each row execute function poison_vendor_chase()");
        double before = meters.counter(ChasingService.FAILURE_METRIC).count();
        clockTo(NY, today(NY), LocalTime.of(10, 0));

        ChasingService.RunResult result = run();

        assertThat(result.chased()).isEqualTo(4);
        assertThat(result.failed()).isEqualTo(1);
        for (UUID g : good) {
            assertThat(chaseRows(g)).isEqualTo(1);
        }
        assertThat(chaseRows(poison)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from notification where organization_id = ?::uuid "
                + "and kind = 'VENDOR_CHASE' and payload ->> 'vendorId' = ?", Integer.class, org, poison.toString()))
                .isZero();
        assertThat(meters.counter(ChasingService.FAILURE_METRIC).count()).isEqualTo(before + 1);
        String text = logText();
        assertThat(text).contains("Chasing vendor failed: organizationId=" + orgId + " vendorId=" + poison
                + " error=");
        assertThat(text).doesNotContain("boom for poison vendor"); // the exception message never reaches the log line

        // Fixed: the next tick retries the vendor that failed (nothing was committed for it).
        jdbc.execute("drop trigger poison_vendor_chase on vendor_chase");
        assertThat(run().chased()).isEqualTo(1);
        assertThat(chaseRows(poison)).isEqualTo(1);
    }

    // ---- M3: global suppression, caps, who may enable ----

    @Test
    void optOutSuppressesTheAddressEverywhere() throws Exception {
        enableDefaults();
        String to = email("supp");
        UUID v = missingVendor(to);
        String token = chaseAndGetOptOutToken(to);

        browser().header("X-Portal-Token", token).perform(HttpMethod.POST, "/api/v1/portal/chasing/opt-out", null, false)
                .andExpect(status().isOk());

        assertThat(suppression.isSuppressed(to)).isTrue();
        assertThat(suppression.isSuppressed(to.toUpperCase())).isTrue(); // case-insensitive
        // Only a hash is stored.
        assertThat(jdbc.queryForObject("select count(*) from email_suppression where email_hash = ?", Integer.class,
                EmailSuppressionService.hash(to))).isEqualTo(1);
        assertThat(jdbc.queryForList("select email_hash from email_suppression", String.class))
                .allMatch(h -> h.length() == 64 && !h.contains("@"));

        // The scheduler: another vendor with the same address (same org) is skipped and reported.
        UUID twin = missingVendor(to);
        clockTo(NY, today(NY).plusDays(30), LocalTime.of(10, 0));
        ChasingService.RunResult r = run();
        assertThat(r.chased()).isZero();
        assertThat(r.skippedSuppressed()).isGreaterThanOrEqualTo(1);
        assertThat(chaseRows(twin)).isZero();

        // Manual document request: 422 for the requester, nothing queued.
        UUID typeId = UUID.fromString(jdbc.queryForObject("select id::text from document_type where organization_id = ?::uuid "
                + "and code = 'W9'", String.class, org));
        member.client().post("/api/v1/vendors/" + twin + "/document-requests", Map.of("documentTypeId", typeId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://vendorflow.app/problems/address-unsubscribed"));

        // Portal link with sendEmail: the link is created, no email is queued, the response says why.
        member.client().post("/api/v1/vendors/" + twin + "/upload-links",
                        Map.of("documentTypeIds", List.of(typeId), "sendEmail", true))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.emailQueued").value(false))
                .andExpect(jsonPath("$.emailSkippedReason").value("ADDRESS_UNSUBSCRIBED"));
        assertThat(mailsTo(to, NotificationKind.DOCUMENT_REQUEST)).isEmpty();
    }

    @Test
    void theDispatcherDropsAQueuedChaseToAnAddressThatGotSuppressedMeanwhile() throws Exception {
        enableDefaults();
        String to = email("late");
        missingVendor(to);
        clockTo(NY, today(NY), LocalTime.of(10, 0));
        assertThat(run().chased()).isEqualTo(1); // PENDING, not dispatched yet
        jdbc.update("insert into email_suppression (email_hash, reason, created_at) values (?, 'CHASING_OPT_OUT', now())",
                EmailSuppressionService.hash(to));

        assertThat(mailsTo(to, NotificationKind.VENDOR_CHASE)).isEmpty();
        Map<String, Object> row = jdbc.queryForMap("select status, payload::text as payload from notification "
                + "where organization_id = ?::uuid and kind = 'VENDOR_CHASE' and lower(recipient_email) = ?", org,
                to.toLowerCase());
        assertThat(row.get("status")).isEqualTo("DEAD");
        assertThat((String) row.get("payload")).doesNotContain("optOutToken").doesNotContain("\"token\"");
    }

    @Test
    void anOrganizationHasADailyChaseCap() throws Exception {
        enableDefaults();
        ReflectionTestUtils.setField(target, "dailyCap", 2);
        List<UUID> vendors = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            vendors.add(missingVendor(email("cap")));
        }
        clockTo(NY, today(NY), LocalTime.of(10, 0));
        ChasingService.RunResult r = run();
        assertThat(r.chased()).isEqualTo(2);
        assertThat(r.skippedOverCap()).isEqualTo(2);
        assertThat(logText()).contains("Chasing daily cap reached");
        // Same local day, later tick: the cap counts what was already chased today.
        assertThat(run().chased()).isZero();
        // Next day the deferred vendors go.
        nextDay(NY, LocalTime.of(10, 0));
        assertThat(run().chased()).isEqualTo(2);
        assertThat(vendors.stream().mapToInt(this::chaseRows).sum()).isEqualTo(4);
    }

    @Test
    void oneRecipientGetsOneChasePerDayAcrossOrganizations() throws Exception {
        String shared = email("shared");
        enableDefaults();
        other.client().put("/api/v1/organization/chasing", settings(true, 7, 4, 30, 9, false)).andExpect(status().isOk());
        UUID mine = missingVendor(shared);
        UUID theirs = fx.vendor(otherOrg, "Their vendor " + UUID.randomUUID());
        jdbc.update("update vendor set email = ? where id = ?", shared, theirs);
        fx.require(otherOrg, theirs, "W9");
        clockTo(NY, today(NY), LocalTime.of(10, 0));

        assertThat(run().chased()).isEqualTo(1);
        ChasingService.RunResult second = chasing.runOrganization(UUID.fromString(otherOrg));
        assertThat(second.chased()).isZero();
        assertThat(second.skippedRecipientCap()).isEqualTo(1);
        assertThat(chaseRows(mine)).isEqualTo(1);
        assertThat(chaseRows(theirs)).isZero();
        assertThat(mailsTo(shared, NotificationKind.VENDOR_CHASE)).hasSize(1);
        jdbc.update("update chasing_settings set enabled = false where organization_id = ?::uuid", otherOrg);
    }

    @Test
    void chasingCanOnlyBeSwitchedOnByAVerifiedOwnerOrAdmin() throws Exception {
        var unverifiedAdmin = accounts.memberOf(org, "ADMIN", "Una Verified");
        unverifiedAdmin.client().put("/api/v1/organization/chasing", settings(true, 7, 4, 30, 9, false))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://vendorflow.app/problems/email-not-verified"));
        assertThat(jdbc.queryForObject("select count(*) from chasing_settings where organization_id = ?::uuid and enabled",
                Integer.class, org)).isZero();
        // Switching OFF (or saving other values while off) never needs it.
        unverifiedAdmin.client().put("/api/v1/organization/chasing", settings(false, 7, 4, 30, 9, false))
                .andExpect(status().isOk());
        // A verified admin can.
        admin.client().put("/api/v1/organization/chasing", settings(true, 7, 4, 30, 9, false)).andExpect(status().isOk());
    }

    // ---- M4: footer, headers, reply-to, one-click ----

    @Test
    void theChaseCarriesIdentificationFooterReplyToAndListUnsubscribeHeaders() throws Exception {
        enableDefaults();
        String to = email("hdr");
        missingVendor(to);
        String token = chaseAndGetOptOutToken(to);
        EmailMessage mail = mailsTo(to, NotificationKind.VENDOR_CHASE).get(0);

        assertThat(mail.textBody()).contains("sent by VendorFlow on behalf of Chasing Org")
                .contains("VendorFlow Test, 1 Test Street, Springfield, USA");
        assertThat(mail.htmlBody()).contains("sent by VendorFlow on behalf of Chasing Org");
        assertThat(mail.headers().get("List-Unsubscribe"))
                .isEqualTo("<http://localhost:3000" + ONE_CLICK + token + ">");
        assertThat(mail.headers().get("List-Unsubscribe-Post")).isEqualTo("List-Unsubscribe=One-Click");
        // The reply goes to a verified staff address of the organization (first by address).
        assertThat(mail.replyTo()).isIn(owner.email(), admin.email());
        List<String> staff = new ArrayList<>(List.of(owner.email().toLowerCase(), admin.email().toLowerCase()));
        java.util.Collections.sort(staff);
        assertThat(mail.replyTo().toLowerCase()).isEqualTo(staff.get(0));
    }

    @Test
    void withoutAPostalAddressNothingIsSent() throws Exception {
        enableDefaults();
        String to = email("nopostal");
        UUID v = missingVendor(to);
        ReflectionTestUtils.setField(target, "postalAddress", "");
        clockTo(NY, today(NY), LocalTime.of(10, 0));

        ChasingService.RunResult r = run();

        assertThat(r.ran()).isFalse();
        assertThat(r.blocked()).isEqualTo("postal-address-missing");
        assertThat(chaseRows(v)).isZero();
        assertThat(mailsTo(to, NotificationKind.VENDOR_CHASE)).isEmpty();
        // Set again: the same day's run goes out (nothing was consumed).
        ReflectionTestUtils.setField(target, "postalAddress", "VendorFlow Test, 1 Test Street");
        assertThat(run().chased()).isEqualTo(1);
    }

    @Test
    void oneClickPostUnsubscribesWithoutSessionOrCsrfAndGetNeverDoes() throws Exception {
        enableDefaults();
        String to = email("oneclick");
        UUID v = missingVendor(to);
        String token = chaseAndGetOptOutToken(to);

        // A link scanner / prefetcher: GET is not allowed and changes nothing.
        browser().perform(HttpMethod.GET, ONE_CLICK + token, null, false).andExpect(status().isMethodNotAllowed());
        assertThat(jdbc.queryForObject("select paused from vendor_chasing where vendor_id = ?", Boolean.class, v)).isFalse();
        assertThat(suppression.isSuppressed(to)).isFalse();

        // The mail client's RFC 8058 POST: no cookie, no CSRF header, form body ignored.
        browser().perform(HttpMethod.POST, ONE_CLICK + token, "List-Unsubscribe=One-Click", false)
                .andExpect(status().isOk()).andExpect(jsonPath("$.optedOut").value(true));
        assertThat(jdbc.queryForObject("select paused_reason from vendor_chasing where vendor_id = ?", String.class, v))
                .isEqualTo("OPT_OUT");
        assertThat(suppression.isSuppressed(to)).isTrue();
        assertThat(auditCount("vendor.chasing.opted_out")).isEqualTo(1);
        // Repeating is harmless and does not notify twice.
        browser().perform(HttpMethod.POST, ONE_CLICK + token, null, false).andExpect(status().isOk());
        assertThat(auditCount("vendor.chasing.opted_out")).isEqualTo(1);
        // And a later GET is still 405, not a leak of the state.
        browser().perform(HttpMethod.GET, ONE_CLICK + token, null, false).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void oneClickBadTokensAreTheSameFourOhFourAsTheHeaderEndpointAndNeverEchoTheToken() throws Exception {
        String unknown = "B".repeat(43);
        var viaPath = browser().perform(HttpMethod.POST, ONE_CLICK + unknown, null, false)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://vendorflow.app/problems/chasing-opt-out-invalid"))
                .andExpect(jsonPath("$.instance").value(not(containsString(unknown))));
        String viaPathBody = viaPath.andReturn().getResponse().getContentAsString();
        String viaHeaderBody = browser().header("X-Portal-Token", unknown)
                .perform(HttpMethod.POST, "/api/v1/portal/chasing/opt-out", null, false).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(viaPathBody).get("type")).isEqualTo(json.readTree(viaHeaderBody).get("type"));
        assertThat(json.readTree(viaPathBody).get("detail")).isEqualTo(json.readTree(viaHeaderBody).get("detail"));
        // Malformed, too long: identical answer.
        browser().perform(HttpMethod.POST, ONE_CLICK + "short", null, false).andExpect(status().isNotFound());
        browser().perform(HttpMethod.POST, ONE_CLICK + "x".repeat(300), null, false).andExpect(status().isNotFound());
    }

    @Test
    void oneClickIsRateLimitedAndTheLimitResponseDoesNotEchoTheToken() throws Exception {
        ApiClient c = new ApiClient(mvc, json).remoteAddr("203.0.113.202");
        String secret = "C".repeat(43);
        for (int i = 0; i < 20; i++) {
            c.perform(HttpMethod.POST, ONE_CLICK + secret, null, false).andExpect(status().isNotFound());
        }
        c.perform(HttpMethod.POST, ONE_CLICK + secret, null, false).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.instance").value(not(containsString(secret))));
        assertThat(logText()).doesNotContain(secret);
    }

    // ---- L1: pause / opt-out races ----

    @Test
    void pausingCancelsTheQueuedChaseAndScrubsItsTokens() throws Exception {
        enableDefaults();
        String to = email("pausecancel");
        UUID v = missingVendor(to);
        clockTo(NY, today(NY), LocalTime.of(10, 0));
        assertThat(run().chased()).isEqualTo(1); // PENDING in the outbox

        owner.client().put("/api/v1/vendors/" + v + "/chasing", Map.of("paused", true)).andExpect(status().isOk());

        Map<String, Object> row = jdbc.queryForMap("select status, payload::text as payload from notification "
                + "where organization_id = ?::uuid and kind = 'VENDOR_CHASE' and payload ->> 'vendorId' = ?", org,
                v.toString());
        assertThat(row.get("status")).isEqualTo("DEAD");
        assertThat((String) row.get("payload")).doesNotContain("optOutToken").doesNotContain("\"token\"");
        assertThat(mailsTo(to, NotificationKind.VENDOR_CHASE)).isEmpty();
    }

    @Test
    void optingOutCancelsTheQueuedChase() throws Exception {
        enableDefaults();
        String to = email("optcancel");
        UUID v = missingVendor(to);
        clockTo(NY, today(NY), LocalTime.of(10, 0));
        assertThat(run().chased()).isEqualTo(1);
        String token = jdbc.queryForObject("select payload ->> 'optOutToken' from notification where "
                + "organization_id = ?::uuid and kind = 'VENDOR_CHASE' and payload ->> 'vendorId' = ?", String.class, org,
                v.toString());

        browser().perform(HttpMethod.POST, ONE_CLICK + token, null, false).andExpect(status().isOk());

        assertThat(mailsTo(to, NotificationKind.VENDOR_CHASE)).isEmpty();
        assertThat(jdbc.queryForObject("select status from notification where organization_id = ?::uuid and "
                + "kind = 'VENDOR_CHASE' and payload ->> 'vendorId' = ?", String.class, org, v.toString()))
                .isEqualTo("DEAD");
    }

    @Test
    void theDispatcherBackstopDropsAChaseOfAVendorPausedBehindTheApisBack() throws Exception {
        enableDefaults();
        String to = email("backstop");
        UUID v = missingVendor(to);
        clockTo(NY, today(NY), LocalTime.of(10, 0));
        assertThat(run().chased()).isEqualTo(1);
        // A pause that did not go through the service (so nothing cancelled the queued row).
        jdbc.update("update vendor_chasing set paused = true, paused_reason = 'MANUAL', paused_at = now() "
                + "where vendor_id = ?", v);

        assertThat(mailsTo(to, NotificationKind.VENDOR_CHASE)).isEmpty();
        assertThat(jdbc.queryForObject("select status from notification where organization_id = ?::uuid and "
                + "kind = 'VENDOR_CHASE' and payload ->> 'vendorId' = ?", String.class, org, v.toString()))
                .isEqualTo("DEAD");
    }

    @Test
    void theClaimRechecksPausedUnderARowLock() {
        UUID fresh = missingVendor(email("lock"));
        TransactionTemplate tt = new TransactionTemplate(txManager);
        // No state row yet: it is created (so a concurrent opt-out serializes against it) and reported not paused.
        Boolean first = tt.execute(s -> chasingStore.lockStateIsPaused(orgId, fresh, java.time.Instant.now()));
        assertThat(first).isFalse();
        assertThat(attempts(fresh)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from vendor_chasing where vendor_id = ?", Integer.class, fresh))
                .isEqualTo(1);
        jdbc.update("update vendor_chasing set paused = true, paused_reason = 'OPT_OUT', paused_at = now() "
                + "where vendor_id = ?", fresh);
        Boolean second = tt.execute(s -> chasingStore.lockStateIsPaused(orgId, fresh, java.time.Instant.now()));
        assertThat(second).isTrue();
    }

    @Test
    void theUniqueAddressDayIndexRefusesASecondChaseOfTheSameAddressWhateverTheOrganization() {
        UUID a = missingVendor(email("uq-a"));
        UUID b = fx.vendor(otherOrg, "Other vendor " + UUID.randomUUID());
        String hash = EmailSuppressionService.hash(email("uq"));
        java.time.Instant now = clock.instant();
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            // rolled back at the end: the deferred link FK would refuse the half-written rows otherwise
            s.setRollbackOnly();
            assertThat(chasingStore.insertChase(orgId, UUID.randomUUID(), a, today(NY), 1, "[]", UUID.randomUUID(),
                    EmailSuppressionService.hash("opt-1"), now.plusSeconds(60), hash, now)).isEqualTo(1);
            assertThat(chasingStore.insertChase(UUID.fromString(otherOrg), UUID.randomUUID(), b, today(NY).plusDays(1), 1,
                    "[]", UUID.randomUUID(), EmailSuppressionService.hash("opt-2"), now.plusSeconds(60), hash, now))
                    .as("same address, same UTC day, other organization").isZero();
        });
    }

    // ---- L2 ----

    @Test
    void aVendorChasedInTheLastTwentyHoursIsSkippedEvenWhenTheLocalDateSaysDue() throws Exception {
        enableDefaults();
        UUID v = missingVendor(email("recent"));
        clockTo(NY, today(NY), LocalTime.of(10, 0));
        // Last chase 5 hours ago but dated far in the past (a time zone change moved the "local date").
        jdbc.update("""
                insert into vendor_chasing (vendor_id, organization_id, episode_attempts, last_chased_at,
                                            last_chased_local_date, updated_at)
                values (?, ?::uuid, 1, ?, ?, now())""", v, org,
                java.sql.Timestamp.from(clock.instant().minus(Duration.ofHours(5))), today(NY).minusDays(20));

        ChasingService.RunResult r = run();

        assertThat(r.chased()).isZero();
        assertThat(chaseRows(v)).isZero();
        // 21 hours later it is allowed again.
        clock.advance(Duration.ofHours(16));
        jdbc.update("update vendor_chasing set last_chased_local_date = ? where vendor_id = ?", today(NY).minusDays(20), v);
        assertThat(run().chased()).isEqualTo(1);
    }
}
