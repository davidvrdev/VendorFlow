package com.vendorflow.reminder;

import com.vendorflow.compliance.application.ComplianceContext;
import com.vendorflow.compliance.application.ComplianceContextService;
import com.vendorflow.compliance.infrastructure.RequirementStatusSql;
import com.vendorflow.notification.NotificationKind;
import com.vendorflow.notification.OutboxService;
import com.vendorflow.organization.application.OrganizationService;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Expiry reminders (ADR-0006, docs/API.md Phase 6): a ledger of crossed thresholds plus one daily digest.
 *
 * <p>Per organization and run, in ONE transaction that first locks the organization row ({@code FOR UPDATE}):
 * <ol>
 *   <li>gate: reminders enabled, org-local time at or after 07:00 and no completed run for the org-local date yet
 *       ({@code organization.last_reminder_run_date}); the lock makes concurrent runs/instances take turns, and the
 *       second one sees the committed date and does nothing;</li>
 *   <li>ledger: insert {@code reminder} rows for every configured offset already crossed (0 &lt;= days left &lt;= offset)
 *       and for EXPIRED documents; the unique constraint makes it idempotent (ON CONFLICT DO NOTHING). Candidate
 *       documents and "today" come from {@link RequirementStatusSql} / {@link ComplianceContextService}, so status
 *       semantics are never duplicated;</li>
 *   <li>digest: not-yet-digested ledger rows that still describe the current state become ONE
 *       {@code COMPLIANCE_DIGEST} per verified OWNER/ADMIN (each document once), then the rows get {@code digested_at}.
 *       Nothing new = nothing sent.</li>
 * </ol>
 * A crash rolls everything back, so the next run redoes the day. Same-day re-runs (only possible with
 * {@code force}) never duplicate rows; a second digest the same day gets a numbered idempotency key.
 */
@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);
    static final LocalTime RUN_AFTER = LocalTime.of(7, 0);
    private static final int MAX_DIGESTS_PER_DAY = 20;

    /** Outcome of one organization run (tests and the e2e endpoint). */
    public record RunResult(boolean ran, int newLedgerRows, int digestsEnqueued) {
        static final RunResult SKIPPED = new RunResult(false, 0, 0);
    }

    private record OrgRow(String name, boolean enabled, List<Integer> offsets, LocalDate lastRun) {
    }

    private static final String ACTIVE_VENDOR_JOIN = """
            join vendor v on v.organization_id = :organizationId and v.id = req.vendor_id and v.status = 'ACTIVE'
            """;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ComplianceContextService contexts;
    private final OrganizationService organizations;
    private final OutboxService outbox;
    private final Clock clock;

    public ReminderService(JdbcClient jdbc, PlatformTransactionManager transactionManager,
            ComplianceContextService contexts, OrganizationService organizations, OutboxService outbox, Clock clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.contexts = contexts;
        this.organizations = organizations;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** Scheduled entry point: every organization with reminders enabled; one failing org does not stop the others. */
    public int runDue() {
        List<UUID> orgs = jdbc.sql("select id from organization where reminders_enabled").query(UUID.class).list();
        int ran = 0;
        for (UUID orgId : orgs) {
            try {
                if (runOrganization(orgId, false).ran()) {
                    ran++;
                }
            } catch (RuntimeException e) {
                // No data in the log: the id identifies the organization, the exception class the problem.
                log.error("Reminder run failed: organizationId={} error={}", orgId, e.getClass().getSimpleName());
            }
        }
        return ran;
    }

    /** @param force skips the 07:00 / once-per-day gates (E2E endpoint only); still serialized and idempotent */
    public RunResult runOrganization(UUID organizationId, boolean force) {
        RunResult result = tx.execute(status -> doRun(organizationId, force));
        return result == null ? RunResult.SKIPPED : result;
    }

    private RunResult doRun(UUID organizationId, boolean force) {
        OrgRow org = jdbc.sql("""
                select name, reminders_enabled, reminder_offsets_days, last_reminder_run_date
                from organization where id = :id for update""")
                .param("id", organizationId)
                .query((rs, i) -> new OrgRow(rs.getString("name"), rs.getBoolean("reminders_enabled"),
                        offsets(rs.getArray("reminder_offsets_days")),
                        rs.getObject("last_reminder_run_date", LocalDate.class)))
                .optional().orElse(null);
        if (org == null || !org.enabled()) {
            return RunResult.SKIPPED;
        }
        ComplianceContext ctx = contexts.forOrganization(organizationId);
        String zone = organizations.complianceSettings(organizationId).timeZone();
        LocalDateTime localNow = contexts.localNow(zone);
        LocalDate today = ctx.today();
        if (!force && (localNow.toLocalTime().isBefore(RUN_AFTER)
                || (org.lastRun() != null && !org.lastRun().isBefore(today)))) {
            return RunResult.SKIPPED;
        }
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);

        int newRows = 0;
        for (int offset : org.offsets()) {
            newRows += insertExpiring(organizationId, ctx, offset, now);
        }
        newRows += insertExpired(organizationId, ctx, now);
        int digests = sendDigest(organizationId, org.name(), ctx, today, now);

        jdbc.sql("update organization set last_reminder_run_date = :today where id = :id")
                .param("today", today).param("id", organizationId).update();
        log.info("Reminder run: organizationId={} newLedgerRows={} digests={}", organizationId, newRows, digests);
        return new RunResult(true, newRows, digests);
    }

    private static List<Integer> offsets(Array array) throws SQLException {
        List<Integer> out = new ArrayList<>();
        if (array != null) {
            for (Object o : (Object[]) array.getArray()) {
                out.add(((Number) o).intValue());
            }
        }
        return out;
    }

    // ---- ledger ----

    private int insertExpiring(UUID organizationId, ComplianceContext ctx, int offset, OffsetDateTime now) {
        return jdbc.sql("with " + RequirementStatusSql.REQ_CTE + """

                insert into reminder (id, organization_id, document_id, kind, offset_days, expiration_date, created_at)
                select gen_random_uuid(), cast(:organizationId as uuid), req.document_id, 'EXPIRING',
                       cast(:offset as int), req.exp_date, cast(:now as timestamptz)
                from req
                """ + ACTIVE_VENDOR_JOIN + """
                where req.exp_date is not null and req.exp_date >= cast(:today as date)
                  and req.exp_date - cast(:today as date) <= cast(:offset as int)
                on conflict (document_id, kind, offset_days, expiration_date) do nothing
                """)
                .param("organizationId", organizationId).param("today", ctx.today())
                .param("windowDays", ctx.windowDays()).param("offset", offset).param("now", now).update();
    }

    private int insertExpired(UUID organizationId, ComplianceContext ctx, OffsetDateTime now) {
        return jdbc.sql("with " + RequirementStatusSql.REQ_CTE + """

                insert into reminder (id, organization_id, document_id, kind, offset_days, expiration_date, created_at)
                select gen_random_uuid(), cast(:organizationId as uuid), req.document_id, 'EXPIRED', 0,
                       req.exp_date, cast(:now as timestamptz)
                from req
                """ + ACTIVE_VENDOR_JOIN + """
                where req.req_status = 'EXPIRED'
                on conflict (document_id, kind, offset_days, expiration_date) do nothing
                """)
                .param("organizationId", organizationId).param("today", ctx.today())
                .param("windowDays", ctx.windowDays()).param("now", now).update();
    }

    // ---- digest ----

    private int sendDigest(UUID organizationId, String organizationName, ComplianceContext ctx, LocalDate today,
            OffsetDateTime now) {
        // Only rows that still describe the current state: the document is still CURRENT with that expiration date,
        // not rejected, and the vendor is still active (a renewal or deactivation since insertion makes them stale).
        List<Map<String, Object>> items = jdbc.sql("""
                select bool_or(rm.kind = 'EXPIRED') as expired, min(rm.expiration_date) as expiration_date,
                       v.company_name, t.name as type_name
                from reminder rm
                join document d on d.organization_id = rm.organization_id and d.id = rm.document_id
                             and d.state = 'CURRENT' and d.review_status <> 'REJECTED'
                             and d.expiration_date = rm.expiration_date
                join vendor v on v.organization_id = d.organization_id and v.id = d.vendor_id and v.status = 'ACTIVE'
                join document_type t on t.organization_id = d.organization_id and t.id = d.document_type_id
                where rm.organization_id = :organizationId and rm.digested_at is null
                group by rm.document_id, v.company_name, t.name
                order by min(rm.expiration_date), lower(v.company_name), t.name
                """)
                .param("organizationId", organizationId)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("expired", rs.getBoolean("expired"));
                    m.put("vendorName", rs.getString("company_name"));
                    m.put("documentType", rs.getString("type_name"));
                    LocalDate exp = rs.getObject("expiration_date", LocalDate.class);
                    m.put("expirationDate", exp.toString());
                    m.put("daysLeft", (int) ChronoUnit.DAYS.between(today, exp));
                    return m;
                }).list();

        int digests = 0;
        if (!items.isEmpty()) {
            List<Map<String, Object>> expired = new ArrayList<>();
            List<Map<String, Object>> expiring = new ArrayList<>();
            for (Map<String, Object> item : items) {
                boolean isExpired = (Boolean) item.remove("expired");
                if (isExpired) {
                    item.remove("daysLeft");
                    expired.add(item);
                } else {
                    expiring.add(item);
                }
            }
            Integer missing = jdbc.sql("with " + RequirementStatusSql.REQ_CTE + """

                    select count(*) from req
                    """ + ACTIVE_VENDOR_JOIN + " where req.req_status = 'MISSING'")
                    .param("organizationId", organizationId).param("today", ctx.today())
                    .param("windowDays", ctx.windowDays()).query(Integer.class).single();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("organizationName", organizationName);
            payload.put("expired", expired);
            payload.put("expiring", expiring);
            payload.put("missingCount", missing);

            List<UUID> recipientIds = new ArrayList<>();
            List<String> recipientEmails = new ArrayList<>();
            jdbc.sql("""
                    select u.id, u.email from membership m join app_user u on u.id = m.user_id
                    where m.organization_id = :organizationId and m.role in ('OWNER', 'ADMIN')
                      and u.email_verified_at is not null
                    order by lower(u.email)""")
                    .param("organizationId", organizationId)
                    .query((rs, i) -> {
                        recipientIds.add(rs.getObject("id", UUID.class));
                        recipientEmails.add(rs.getString("email"));
                        return 0;
                    }).list();
            for (int r = 0; r < recipientIds.size(); r++) {
                String baseKey = "digest:" + organizationId + ":" + recipientIds.get(r) + ":" + today;
                for (int n = 1; n <= MAX_DIGESTS_PER_DAY; n++) {
                    String key = n == 1 ? baseKey : baseKey + ":" + n;
                    if (outbox.enqueue(NotificationKind.COMPLIANCE_DIGEST, organizationId, recipientEmails.get(r), key,
                            payload)) {
                        digests++;
                        break;
                    }
                }
            }
            if (recipientIds.isEmpty()) {
                log.info("Reminder digest has no recipient (no verified OWNER/ADMIN): organizationId={}",
                        organizationId);
            }
        }
        // Everything pending is now accounted for (including stale rows that were filtered out above).
        jdbc.sql("update reminder set digested_at = :now where organization_id = :organizationId "
                + "and digested_at is null")
                .param("now", now).param("organizationId", organizationId).update();
        return digests;
    }
}
