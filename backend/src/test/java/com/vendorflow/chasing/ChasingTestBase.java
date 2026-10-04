package com.vendorflow.chasing;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.chasing.application.ChasingService;
import com.vendorflow.document.domain.ReviewStatus;
import com.vendorflow.notification.EmailMessage;
import com.vendorflow.notification.NotificationKind;
import com.vendorflow.notification.OutboxDispatcher;
import com.vendorflow.support.ComplianceFixtures;
import com.vendorflow.support.EmailTokens;
import com.vendorflow.support.IntegrationTest;
import com.vendorflow.support.TestAccounts;
import com.vendorflow.support.TestAccounts.Account;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fixtures of the chasing tests: one organization with all four roles (owner and admin verified, so they are staff
 * e-mail recipients), one unrelated organization, JDBC compliance fixtures and helpers to move the MutableClock to an
 * org-local date/time. The scheduler itself is off in the test profile: tests call {@link ChasingService} directly.
 */
public abstract class ChasingTestBase extends IntegrationTest {

    static final String NY = "America/New_York";
    static final Pattern UPLOAD_TOKEN = Pattern.compile("/portal#token=([A-Za-z0-9_-]+)");
    static final Pattern OPT_OUT_TOKEN = Pattern.compile("/portal/unsubscribe#token=([A-Za-z0-9_-]+)");

    @Autowired protected MockMvc mvc;
    @Autowired protected JsonMapper json;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ChasingService chasing;
    @Autowired protected OutboxDispatcher dispatcher;

    protected ComplianceFixtures fx;
    protected TestAccounts accounts;
    protected Account owner;
    protected Account admin;
    protected Account member;
    protected Account viewer;
    protected Account other;
    protected String org;
    protected UUID orgId;
    protected String otherOrg;

    @BeforeEach
    void setUpChasing() throws Exception {
        fx = new ComplianceFixtures(jdbc);
        accounts = new TestAccounts(mvc, json, jdbc);
        owner = accounts.verified(accounts.signup(TestAccounts.uniqueEmail(), TestAccounts.PASSWORD, "Dora Owner",
                "Chasing Org " + UUID.randomUUID()));
        org = owner.organizationId();
        orgId = UUID.fromString(org);
        admin = accounts.verified(accounts.memberOf(org, "ADMIN", "Adam Admin"));
        member = accounts.memberOf(org, "MEMBER", "Mia Member");
        viewer = accounts.memberOf(org, "VIEWER", "Vic Viewer");
        other = accounts.verified(accounts.signup(TestAccounts.uniqueEmail(), TestAccounts.PASSWORD, "Olga Other",
                "Other Chasing Org " + UUID.randomUUID()));
        otherOrg = other.organizationId();
        clockTo(NY, LocalDate.ofInstant(clock.instant(), ZoneId.of(NY)), LocalTime.of(12, 0));
    }

    /** Tests share one database and a JVM-wide scheduler-less context: switch this org off so no later run touches it. */
    @AfterEach
    void switchChasingOff() {
        jdbc.update("update chasing_settings set enabled = false where organization_id in (?::uuid, ?::uuid)", org,
                otherOrg);
    }

    // ---- clock ----

    /** Moves the MutableClock so that it is {@code time} on {@code date} in {@code zone}. */
    protected void clockTo(String zone, LocalDate date, LocalTime time) {
        Instant target = date.atTime(time).atZone(ZoneId.of(zone)).toInstant();
        clock.advance(Duration.between(clock.instant(), target));
    }

    protected LocalDate today(String zone) {
        return LocalDate.ofInstant(clock.instant(), ZoneId.of(zone));
    }

    /** Next org-local day at the given time. */
    protected void nextDay(String zone, LocalTime time) {
        clockTo(zone, today(zone).plusDays(1), time);
    }

    // ---- settings ----

    protected Map<String, Object> settings(boolean enabled, int cadence, int max, int lead, int hour, boolean cc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("cadenceDays", cadence);
        m.put("maxAttempts", max);
        m.put("leadDays", lead);
        m.put("sendHourLocal", hour);
        m.put("ccStaff", cc);
        return m;
    }

    protected void enable(int cadence, int max, int lead, int hour, boolean cc) throws Exception {
        owner.client().put("/api/v1/organization/chasing", settings(true, cadence, max, lead, hour, cc))
                .andExpect(status().isOk());
    }

    protected void enableDefaults() throws Exception {
        enable(7, 4, 30, 9, false);
    }

    // ---- vendors ----

    protected UUID vendor(String name, String email) {
        UUID v = fx.vendor(org, name);
        if (email != null) {
            jdbc.update("update vendor set email = ? where id = ?", email, v);
        }
        return v;
    }

    /** An ACTIVE vendor with a MISSING W9 requirement (no document) and the given email. */
    protected UUID missingVendor(String email) {
        UUID v = vendor("Vendor " + UUID.randomUUID(), email);
        fx.require(org, v, "W9");
        return v;
    }

    /** Makes the vendor's W9 (no expiration) satisfied by an approved CURRENT document. */
    protected void satisfyW9(UUID vendor) {
        fx.document(org, vendor, "W9", "CURRENT", ReviewStatus.APPROVED, null, Instant.now());
    }

    protected void removeDocuments(UUID vendor) {
        jdbc.update("delete from document where vendor_id = ?", vendor);
    }

    protected static String email(String prefix) {
        return prefix + "." + UUID.randomUUID() + "@vendor.example.com";
    }

    // ---- observation ----

    protected ChasingService.RunResult run() {
        return chasing.runOrganization(orgId);
    }

    protected List<EmailMessage> mailsTo(String to, NotificationKind kind) {
        while (dispatcher.dispatchBatch() > 0) {
            // drain the outbox like the real dispatcher would
        }
        return emailSender.sent().stream().filter(m -> m.to().equalsIgnoreCase(to) && m.kind() == kind).toList();
    }

    protected EmailMessage latestMail(String to, NotificationKind kind) {
        return EmailTokens.latest(dispatcher, emailSender, to, kind);
    }

    protected int chaseRows(UUID vendor) {
        return jdbc.queryForObject("select count(*) from vendor_chase where vendor_id = ?", Integer.class, vendor);
    }

    protected int attempts(UUID vendor) {
        return jdbc.query("select episode_attempts from vendor_chasing where vendor_id = ?",
                rs -> rs.next() ? rs.getInt(1) : 0, vendor);
    }

    protected int auditCount(String action) {
        return jdbc.queryForObject("select count(*) from audit_event where organization_id = ?::uuid and action = ?",
                Integer.class, org, action);
    }

    protected static String group(Pattern p, String body) {
        Matcher m = p.matcher(body);
        if (!m.find()) {
            throw new AssertionError("no match for " + p + " in mail body");
        }
        return m.group(1);
    }

    protected JsonNode body(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }
}
