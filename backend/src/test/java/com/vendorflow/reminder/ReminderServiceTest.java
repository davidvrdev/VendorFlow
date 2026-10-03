package com.vendorflow.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import com.vendorflow.compliance.application.ComplianceContextService;
import com.vendorflow.document.domain.ReviewStatus;
import com.vendorflow.notification.EmailMessage;
import com.vendorflow.notification.NotificationKind;
import com.vendorflow.notification.OutboxDispatcher;
import com.vendorflow.support.ComplianceFixtures;
import com.vendorflow.support.IntegrationTest;
import com.vendorflow.support.TestAccounts;
import com.vendorflow.support.TestAccounts.Account;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** Ledger + digest behavior, driven by the MutableClock (the scheduler is off in the test profile). */
class ReminderServiceTest extends IntegrationTest {

    static final String NY = "America/New_York";

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReminderService reminders;
    @Autowired ComplianceContextService contexts;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired ApplicationContext context;

    ComplianceFixtures fx;
    TestAccounts accounts;
    Account owner;
    String org;
    UUID orgId;

    @BeforeEach
    void setUp() throws Exception {
        fx = new ComplianceFixtures(jdbc);
        accounts = new TestAccounts(mvc, json, jdbc);
        owner = accounts.verified(accounts.signup(TestAccounts.uniqueEmail(), TestAccounts.PASSWORD, "Dora Owner",
                "Reminder Org " + UUID.randomUUID()));
        org = owner.organizationId();
        orgId = UUID.fromString(org);
        setLocalTime(NY, LocalTime.of(12, 0));
    }

    // ---- helpers ----

    /** Moves the clock so that it is {@code time} (org-local) on the current local date. */
    void setLocalTime(String zone, LocalTime time) {
        ZoneId z = ZoneId.of(zone);
        Instant target = LocalDate.ofInstant(clock.instant(), z).atTime(time).atZone(z).toInstant();
        clock.advance(Duration.between(clock.instant(), target));
    }

    LocalDate today() {
        return contexts.todayIn(NY);
    }

    int ledgerRows(String organization) {
        return jdbc.queryForObject("select count(*) from reminder where organization_id = ?::uuid", Integer.class,
                organization);
    }

    int digests(String organization) {
        return jdbc.queryForObject(
                "select count(*) from notification where organization_id = ?::uuid and kind = 'COMPLIANCE_DIGEST'",
                Integer.class, organization);
    }

    UUID expiringVendor(String name, int daysFromToday) {
        UUID v = fx.vendor(org, name);
        fx.requirement(org, v, "COI", ReviewStatus.APPROVED, today().plusDays(daysFromToday));
        return v;
    }

    // ---- thresholds over time ----

    @Test
    void thresholdsFireOncePerCrossingOverFortySimulatedDays() {
        expiringVendor("Acme Plumbing", 35);
        TreeSet<Integer> digestDays = new TreeSet<>();
        int previous = 0;
        for (int day = 0; day <= 40; day++) {
            if (day > 0) {
                clock.advance(Duration.ofDays(1));
            }
            assertThat(reminders.runOrganization(orgId, false).ran()).as("day " + day).isTrue();
            int now = digests(org);
            if (now > previous) {
                assertThat(now - previous).as("one digest for the single recipient, day " + day).isEqualTo(1);
                digestDays.add(day);
            }
            previous = now;
        }
        // 30 days left on day 5, 14 on day 21, 7 on day 28, 1 on day 34; expired from day 36. Day 35 (0 days left,
        // expires today) crosses nothing new.
        assertThat(digestDays).containsExactly(5, 21, 28, 34, 36);
        List<String> rows = jdbc.queryForList(
                "select kind || ':' || offset_days from reminder where organization_id = ?::uuid order by 1",
                String.class, org);
        assertThat(rows).containsExactly("EXPIRED:0", "EXPIRING:1", "EXPIRING:14", "EXPIRING:30", "EXPIRING:7");
        assertThat(jdbc.queryForObject(
                "select count(*) from reminder where organization_id = ?::uuid and digested_at is null", Integer.class,
                org)).isZero();
    }

    @Test
    void aDocumentFirstSeenInsideSeveralThresholdsCreatesOneRowPerThresholdButIsListedOnceInTheDigest() {
        expiringVendor("Acme Plumbing", 5);
        var result = reminders.runOrganization(orgId, false);
        assertThat(result.newLedgerRows()).isEqualTo(3); // 30, 14, 7
        assertThat(result.digestsEnqueued()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select payload -> 'expiring' ->> 0 from notification "
                + "where organization_id = ?::uuid and kind = 'COMPLIANCE_DIGEST'", String.class, org))
                .contains("Acme Plumbing");
        assertThat(jdbc.queryForObject("select jsonb_array_length(payload -> 'expiring') from notification "
                + "where organization_id = ?::uuid and kind = 'COMPLIANCE_DIGEST'", Integer.class, org)).isEqualTo(1);
    }

    @Test
    void rerunningTheSameDayCreatesNothingNew() {
        expiringVendor("Acme Plumbing", 5);
        assertThat(reminders.runOrganization(orgId, false).ran()).isTrue();
        assertThat(reminders.runOrganization(orgId, false).ran()).isFalse();
        // Even a forced run (e2e endpoint) finds nothing new and sends nothing.
        var forced = reminders.runOrganization(orgId, true);
        assertThat(forced.ran()).isTrue();
        assertThat(forced.newLedgerRows()).isZero();
        assertThat(forced.digestsEnqueued()).isZero();
        assertThat(ledgerRows(org)).isEqualTo(3);
        assertThat(digests(org)).isEqualTo(1);
    }

    @Test
    void twoConcurrentRunsNeverDuplicate() throws Exception {
        expiringVendor("Acme Plumbing", 5);
        UUID expired = fx.vendor(org, "Old Co");
        fx.requirement(org, expired, "COI", ReviewStatus.APPROVED, today().minusDays(3));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<ReminderService.RunResult>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return reminders.runOrganization(orgId, false);
                }));
            }
            go.countDown();
            int ran = 0;
            for (Future<ReminderService.RunResult> f : futures) {
                if (f.get().ran()) {
                    ran++;
                }
            }
            assertThat(ran).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(ledgerRows(org)).isEqualTo(4); // 3 thresholds + 1 EXPIRED
        assertThat(digests(org)).isEqualTo(1);
    }

    @Test
    void concurrentForcedRunsAreSerializedAndStillDuplicateFree() throws Exception {
        expiringVendor("Acme Plumbing", 5);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<ReminderService.RunResult>> futures = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return reminders.runOrganization(orgId, true);
                }));
            }
            go.countDown();
            int newRows = 0;
            for (Future<ReminderService.RunResult> f : futures) {
                newRows += f.get().newLedgerRows();
            }
            assertThat(newRows).isEqualTo(3);
        } finally {
            pool.shutdownNow();
        }
        assertThat(ledgerRows(org)).isEqualTo(3);
        assertThat(digests(org)).isEqualTo(1);
    }

    @Test
    void renewalWithANewExpirationDateRestartsTheThresholds() {
        UUID v = expiringVendor("Acme Plumbing", 10);
        LocalDate oldDate = today().plusDays(10);
        reminders.runOrganization(orgId, false);
        assertThat(ledgerRows(org)).isEqualTo(2); // 30 and 14
        LocalDate newDate = today().plusDays(40);
        jdbc.update("update document set expiration_date = ? where vendor_id = ?", newDate, v);
        for (int day = 1; day <= 30; day++) {
            clock.advance(Duration.ofDays(1));
            reminders.runOrganization(orgId, false);
        }
        List<Integer> oldOffsets = jdbc.queryForList(
                "select offset_days from reminder where organization_id = ?::uuid and expiration_date = ? order by 1",
                Integer.class, org, oldDate);
        List<Integer> newOffsets = jdbc.queryForList(
                "select offset_days from reminder where organization_id = ?::uuid and expiration_date = ? order by 1",
                Integer.class, org, newDate);
        assertThat(oldOffsets).containsExactly(14, 30);
        assertThat(newOffsets).containsExactly(14, 30); // day 10 -> 30 days left, day 26 -> 14 days left
    }

    @Test
    void expiredIsReportedOnceAndNeverAgain() {
        expiringVendor("Old Co", -1);
        var first = reminders.runOrganization(orgId, false);
        assertThat(first.newLedgerRows()).isEqualTo(1);
        assertThat(first.digestsEnqueued()).isEqualTo(1);
        for (int day = 1; day <= 5; day++) {
            clock.advance(Duration.ofDays(1));
            var again = reminders.runOrganization(orgId, false);
            assertThat(again.newLedgerRows()).isZero();
            assertThat(again.digestsEnqueued()).isZero();
        }
        assertThat(jdbc.queryForObject(
                "select count(*) from reminder where organization_id = ?::uuid and kind = 'EXPIRED'", Integer.class,
                org)).isEqualTo(1);
        assertThat(digests(org)).isEqualTo(1);
    }

    // ---- exclusions ----

    @Test
    void onlyEligibleDocumentsProduceReminders() {
        expiringVendor("Included", 5); // 3 rows
        // inactive vendor
        UUID inactive = fx.vendor(org, "Inactive Vendor", "INACTIVE");
        fx.requirement(org, inactive, "COI", ReviewStatus.APPROVED, today().plusDays(5));
        // inactive document type
        UUID onInactiveType = fx.vendor(org, "On Inactive Type");
        fx.requirement(org, onInactiveType, "BUSINESS_LICENSE", ReviewStatus.APPROVED, today().plusDays(5));
        jdbc.update("update document_type set active = false where organization_id = ?::uuid and code = 'BUSINESS_LICENSE'",
                org);
        // rejected document
        UUID rejected = fx.vendor(org, "Rejected Doc");
        fx.requirement(org, rejected, "PROFESSIONAL_LICENSE", ReviewStatus.REJECTED, today().plusDays(5));
        // type without expiration (the date is ignored)
        UUID noExpiration = fx.vendor(org, "No Expiration Type");
        fx.requirement(org, noExpiration, "W9", ReviewStatus.APPROVED, today().minusDays(5));
        // superseded (not CURRENT) document
        UUID superseded = fx.vendor(org, "Superseded Doc");
        fx.require(org, superseded, "GENERAL_LIABILITY");
        fx.document(org, superseded, "GENERAL_LIABILITY", "SUPERSEDED", ReviewStatus.APPROVED, today().plusDays(5),
                Instant.now());
        // document without a requirement (not tracked by compliance anywhere)
        UUID untracked = fx.vendor(org, "Untracked Doc");
        fx.document(org, untracked, "WORKERS_COMP", "CURRENT", ReviewStatus.APPROVED, today().plusDays(5),
                Instant.now());

        reminders.runOrganization(orgId, false);

        assertThat(ledgerRows(org)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(distinct document_id) from reminder where organization_id = ?::uuid",
                Integer.class, org)).isEqualTo(1);
    }

    @Test
    void pendingReviewDocumentsStillGetReminders() {
        UUID v = fx.vendor(org, "Pending Doc");
        fx.requirement(org, v, "COI", ReviewStatus.PENDING, today().plusDays(5));
        assertThat(reminders.runOrganization(orgId, false).newLedgerRows()).isEqualTo(3);
    }

    @Test
    void organizationsWithRemindersDisabledAreSkipped() {
        expiringVendor("Acme Plumbing", 5);
        jdbc.update("update organization set reminders_enabled = false where id = ?::uuid", org);
        var result = reminders.runOrganization(orgId, false);
        assertThat(result.ran()).isFalse();
        assertThat(reminders.runOrganization(orgId, true).ran()).isFalse();
        assertThat(reminders.runDue()).isGreaterThanOrEqualTo(0);
        assertThat(ledgerRows(org)).isZero();
        assertThat(digests(org)).isZero();
    }

    @Test
    void customOffsetsAreHonored() {
        jdbc.update("update organization set reminder_offsets_days = '{3}' where id = ?::uuid", org);
        expiringVendor("Acme Plumbing", 5);
        assertThat(reminders.runOrganization(orgId, false).newLedgerRows()).isZero();
        clock.advance(Duration.ofDays(2));
        assertThat(reminders.runOrganization(orgId, false).newLedgerRows()).isEqualTo(1);
    }

    @Test
    void theSchedulerIsNotPartOfTheTestContext() {
        assertThat(context.containsBean("reminderScheduler")).isFalse();
    }

    // ---- time zone and the 07:00 gate ----

    @Test
    void nothingRunsBeforeSevenLocalAndOnlyOncePerLocalDay() {
        expiringVendor("Acme Plumbing", 5);
        setLocalTime(NY, LocalTime.of(6, 30));
        assertThat(reminders.runOrganization(orgId, false).ran()).isFalse();
        assertThat(ledgerRows(org)).isZero();
        clock.advance(Duration.ofMinutes(31)); // 07:01
        assertThat(reminders.runOrganization(orgId, false).ran()).isTrue();
        assertThat(reminders.runOrganization(orgId, false).ran()).isFalse();
        clock.advance(Duration.ofHours(22)); // next day, before 07:00 again
        assertThat(reminders.runOrganization(orgId, false).ran()).isFalse();
        clock.advance(Duration.ofHours(2));
        assertThat(reminders.runOrganization(orgId, false).ran()).isTrue();
    }

    @Test
    void eachOrganizationUsesItsOwnTimeZoneForTheGateAndForToday() throws Exception {
        // Clock: 12:00 in New York = 01:00 (next day) in Tokyo.
        Account tokyoOwner = accounts.verified(accounts.signup(TestAccounts.uniqueEmail(), TestAccounts.PASSWORD,
                "Taka Owner", "Tokyo Org " + UUID.randomUUID()));
        String tokyo = tokyoOwner.organizationId();
        jdbc.update("update organization set time_zone = 'Asia/Tokyo' where id = ?::uuid", tokyo);
        UUID tokyoVendor = fx.vendor(tokyo, "Tokyo Vendor");
        fx.requirement(tokyo, tokyoVendor, "COI", ReviewStatus.APPROVED, contexts.todayIn("Asia/Tokyo").minusDays(1));
        expiringVendor("Acme Plumbing", 5);

        assertThat(reminders.runOrganization(UUID.fromString(tokyo), false).ran()).as("01:00 in Tokyo").isFalse();
        assertThat(reminders.runOrganization(orgId, false).ran()).as("12:00 in New York").isTrue();

        clock.advance(Duration.ofHours(7)); // 08:00 in Tokyo
        var result = reminders.runOrganization(UUID.fromString(tokyo), false);
        assertThat(result.ran()).isTrue();
        assertThat(result.newLedgerRows()).isEqualTo(1); // EXPIRED: yesterday in TOKYO, not in New York
        assertThat(ledgerRows(tokyo)).isEqualTo(1);
    }

    // ---- recipients and content ----

    @Test
    void digestGoesOnlyToVerifiedOwnersAndAdmins() throws Exception {
        Account admin = accounts.verified(accounts.memberOf(org, "ADMIN", "Adam Admin"));
        Account unverifiedAdmin = accounts.memberOf(org, "ADMIN", "Una Unverified");
        Account member = accounts.verified(accounts.memberOf(org, "MEMBER", "Mia Member"));
        Account viewer = accounts.verified(accounts.memberOf(org, "VIEWER", "Vic Viewer"));
        expiringVendor("Acme Plumbing", 5);

        assertThat(reminders.runOrganization(orgId, false).digestsEnqueued()).isEqualTo(2);

        List<String> recipients = jdbc.queryForList("select recipient_email from notification "
                + "where organization_id = ?::uuid and kind = 'COMPLIANCE_DIGEST'", String.class, org);
        assertThat(recipients).containsExactlyInAnyOrder(owner.email(), admin.email())
                .doesNotContain(unverifiedAdmin.email(), member.email(), viewer.email());
    }

    @Test
    void noRecipientMeansNothingIsEnqueuedButTheLedgerIsStillRecorded() {
        jdbc.update("update app_user set email_verified_at = null where id = ?::uuid", owner.userId());
        expiringVendor("Acme Plumbing", 5);
        var result = reminders.runOrganization(orgId, false);
        assertThat(result.newLedgerRows()).isEqualTo(3);
        assertThat(result.digestsEnqueued()).isZero();
    }

    @Test
    void digestContentIsEscapedLinksTheDashboardAndListsEachDocumentOnce() {
        jdbc.update("delete from notification");
        String evil = "<script>alert(1)</script> & Sons";
        expiringVendor(evil, 5);
        expiringVendor("Old Vendor", -2);
        UUID missing = fx.vendor(org, "Missing Vendor");
        fx.require(org, missing, "W9");

        reminders.runOrganization(orgId, false);
        dispatcher.dispatchBatch();

        List<EmailMessage> mine = emailSender.sent().stream()
                .filter(m -> m.to().equals(owner.email()) && m.kind() == NotificationKind.COMPLIANCE_DIGEST).toList();
        assertThat(mine).hasSize(1);
        EmailMessage digest = mine.get(0);
        assertThat(digest.subject()).isEqualTo("VendorFlow: 1 document expired, 1 expiring soon");
        assertThat(digest.htmlBody()).contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; Sons")
                .doesNotContain("<script>");
        assertThat(digest.htmlBody()).contains("href=\"http://localhost:3000/dashboard\"");
        assertThat(digest.textBody()).contains("http://localhost:3000/dashboard").contains("Old Vendor")
                .contains("1 required document is currently missing");
        assertThat(count(digest.textBody(), "Sons")).isEqualTo(1);
        assertThat(count(digest.textBody(), "Old Vendor")).isEqualTo(1);
        assertThat(digest.textBody()).contains("5 days left");
    }

    @Test
    void subjectForOnlyExpiringDocumentsHasNoExpiredPart() {
        jdbc.update("delete from notification");
        expiringVendor("A", 5);
        expiringVendor("B", 6);
        reminders.runOrganization(orgId, false);
        dispatcher.dispatchBatch();
        assertThat(emailSender.sent().stream().filter(m -> m.to().equals(owner.email())).map(EmailMessage::subject))
                .containsExactly("VendorFlow: 2 documents expiring soon");
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    @Test
    void aVendorDeactivatedAfterTheLedgerRunIsNotReportedLater() {
        UUID v = expiringVendor("Acme Plumbing", 5);
        // Ledger rows exist but were never digested (e.g. nobody could receive it); then the vendor is deactivated.
        jdbc.update("update app_user set email_verified_at = null where id = ?::uuid", owner.userId());
        reminders.runOrganization(orgId, false);
        jdbc.update("update app_user set email_verified_at = now() where id = ?::uuid", owner.userId());
        jdbc.update("update vendor set status = 'INACTIVE' where id = ?", v);
        clock.advance(Duration.ofDays(1));
        assertThat(reminders.runOrganization(orgId, false).digestsEnqueued()).isZero();
        assertThat(digests(org)).isZero();
    }
}
