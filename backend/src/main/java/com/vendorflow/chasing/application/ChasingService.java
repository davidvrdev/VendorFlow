package com.vendorflow.chasing.application;

import com.vendorflow.audit.AuditService;
import com.vendorflow.billing.application.SubscriptionService;
import com.vendorflow.chasing.infrastructure.ChasingSettingsStore;
import com.vendorflow.chasing.infrastructure.ChasingStore;
import com.vendorflow.chasing.infrastructure.ChasingStore.Candidate;
import com.vendorflow.chasing.infrastructure.ChasingStore.Deficiency;
import com.vendorflow.compliance.application.ComplianceContextService;
import com.vendorflow.identity.application.TokenGenerator;
import com.vendorflow.notification.EmailSuppressionService;
import com.vendorflow.notification.NotificationKind;
import com.vendorflow.notification.OutboxService;
import com.vendorflow.organization.application.MembershipService;
import com.vendorflow.organization.domain.StaffRecipient;
import com.vendorflow.portal.application.PortalLinkService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Automated chasing engine (ADR-0012).
 *
 * <p><b>Exclusion.</b> One organization is processed by at most one tick at a time: the run first takes the
 * transaction-scoped advisory lock {@code pg_try_advisory_xact_lock('chasing-org:' || id)} and skips the organization
 * when it is taken (a concurrent tick or instance; ADR-0006, no ShedLock). It deliberately locks no row of
 * {@code organization}: a {@code FOR UPDATE} there would block every child-table insert of the tenant (vendor, upload,
 * opt-out take {@code FOR KEY SHARE} on the parent through their foreign keys) for the whole run. The lock transaction does
 * nothing else and stays open only to hold the lock; all the work runs in short transactions of its own.
 *
 * <p><b>Phases.</b> (1) one transaction: organization, settings, episode reset, the deficient vendors, the recipients
 * already emailed today, the suppressed addresses; (2) in memory: who is due; (3) the due vendors in batches of
 * {@code app.chasing.batch-size} (50), each batch ONE transaction: per vendor state lock + re-check, ledger claim
 * ({@code UNIQUE (vendor_id, local_date)}), fresh portal link, outbox email, state, audit; the previous chase links are
 * revoked in one statement per batch; (4) one transaction: staff notices and the "exhausted" marks.
 *
 * <p><b>Isolation (M2).</b> A batch that fails rolls back as a whole and its vendors are then retried one per
 * transaction, so a poison vendor costs only itself: it is logged (organization id, vendor id, exception class), counted
 * and skipped, and the other vendors are committed. Nothing is committed for a failed vendor, so the next tick retries it.
 *
 * <p><b>Limits.</b> Per organization {@code app.chasing.max-per-org-per-day} chases per local day; per recipient ONE
 * chase per 24 hours across ALL organizations; one email per recipient per local day across chases and manual requests;
 * never to a suppressed (unsubscribed) address; never when the vendor is paused/opted out (re-checked under a row lock
 * right before the claim).
 */
@Service
public class ChasingService {

    private static final Logger log = LoggerFactory.getLogger(ChasingService.class);

    /** Opt-out links stay valid long after the upload link: an old email must still be able to unsubscribe. */
    static final Duration OPT_OUT_LIFETIME = Duration.ofDays(180);
    /** The upload link outlives the cadence by a week so the vendor always has a live link until the next chase. */
    static final int LINK_GRACE_DAYS = 7;
    /** Belt and braces on top of the local-date cadence (a time zone change must not double-chase a vendor). */
    static final Duration MIN_SPACING = Duration.ofHours(20);
    /** Per recipient, across organizations. */
    static final Duration RECIPIENT_CAP_WINDOW = Duration.ofHours(20);
    /** Counter name of vendors that failed while being chased (alert on it, docs/RUNBOOKS.md). */
    public static final String FAILURE_METRIC = "vendorflow.chasing.vendor.failures";
    /** Stable log message of a failed vendor (alert on it, docs/RUNBOOKS.md). */
    static final String FAILURE_LOG = "Chasing vendor failed: organizationId={} vendorId={} error={}";

    /**
     * Outcome of one organization run (tests, e2e). {@code blocked} names why a run that was due did not send
     * ("postal-address-missing", "no-staff-contact"), else null.
     */
    public record RunResult(boolean ran, int chased, int skippedSameDay, int exhaustedNotified, int skippedSuppressed,
            int skippedOverCap, int skippedRecipientCap, int skippedPaused, int failed, String blocked) {
        static final RunResult SKIPPED = new RunResult(false, 0, 0, 0, 0, 0, 0, 0, 0, null);

        static RunResult blocked(String reason) {
            return new RunResult(false, 0, 0, 0, 0, 0, 0, 0, 0, reason);
        }
    }

    private record OrgRow(String name, String timeZone) {
    }

    /** Everything phase 1 loaded; {@code early} is set when the run ends there. */
    private record Prep(RunResult early, OrgRow org, ChasingSettings settings, LocalDate today, Instant now,
            List<Candidate> candidates, Set<String> emailedToday, Set<String> suppressed, Set<String> recentlyChased,
            int usedToday,
            List<StaffRecipient> staff) {
        static Prep early(RunResult r) {
            return new Prep(r, null, null, null, null, null, null, null, null, 0, null);
        }
    }

    /** What a batch (or the whole run) did. Merged only after the batch committed. */
    private static final class Tally {
        final List<Map<String, Object>> chasedItems = new ArrayList<>();
        int skippedPaused;
        int skippedSuppressed;
        int skippedRecipientCap;
        int failed;

        void add(Tally o) {
            chasedItems.addAll(o.chasedItems);
            skippedPaused += o.skippedPaused;
            skippedSuppressed += o.skippedSuppressed;
            skippedRecipientCap += o.skippedRecipientCap;
            failed += o.failed;
        }
    }

    /** Run-scoped values the batch code needs. */
    private record Ctx(UUID organizationId, String organizationName, ChasingSettings settings, LocalDate today,
            Instant now, String replyTo) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate lockTx;
    private final TransactionTemplate newTx;
    private final ChasingSettingsStore settingsStore;
    private final ChasingStore store;
    private final ComplianceContextService contexts;
    private final SubscriptionService subscriptions;
    private final PortalLinkService portalLinks;
    private final MembershipService memberships;
    private final OutboxService outbox;
    private final EmailSuppressionService suppression;
    private final AuditService audit;
    private final TokenGenerator tokens;
    private final JsonMapper json;
    private final Clock clock;
    private final MeterRegistry meters;
    private final String postalAddress;
    private final int dailyCap;
    private final int batchSize;
    private final AtomicBoolean postalWarned = new AtomicBoolean();

    public ChasingService(JdbcClient jdbc, PlatformTransactionManager transactionManager,
            ChasingSettingsStore settingsStore, ChasingStore store, ComplianceContextService contexts,
            SubscriptionService subscriptions, PortalLinkService portalLinks, MembershipService memberships,
            OutboxService outbox, EmailSuppressionService suppression, AuditService audit, TokenGenerator tokens,
            JsonMapper json, Clock clock, MeterRegistry meters,
            @Value("${vendorflow.mail.postal-address:}") String postalAddress,
            @Value("${app.chasing.max-per-org-per-day:200}") int dailyCap,
            @Value("${app.chasing.batch-size:50}") int batchSize) {
        this.jdbc = jdbc;
        this.lockTx = new TransactionTemplate(transactionManager);
        this.newTx = new TransactionTemplate(transactionManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.settingsStore = settingsStore;
        this.store = store;
        this.contexts = contexts;
        this.subscriptions = subscriptions;
        this.portalLinks = portalLinks;
        this.memberships = memberships;
        this.outbox = outbox;
        this.suppression = suppression;
        this.audit = audit;
        this.tokens = tokens;
        this.json = json;
        this.clock = clock;
        this.meters = meters;
        this.postalAddress = postalAddress == null ? "" : postalAddress.trim();
        this.dailyCap = Math.max(1, dailyCap);
        this.batchSize = Math.max(1, batchSize);
    }

    /** Scheduled entry point: every enabled organization whose local time reached its send hour; failures are isolated. */
    public int runDue() {
        int chased = 0;
        for (ChasingSettingsStore.EnabledOrganization org : settingsStore.findEnabled()) {
            try {
                LocalDateTime localNow = contexts.localNow(org.timeZone());
                if (localNow.getHour() < org.sendHourLocal() || subscriptions.isReadOnly(org.organizationId())) {
                    continue; // cheap pre-check without a transaction or a lock
                }
                chased += runOrganization(org.organizationId()).chased();
            } catch (RuntimeException e) {
                // The id identifies the organization, the class the problem: no vendor data in the log.
                log.error("Chasing run failed: organizationId={} error={}", org.organizationId(),
                        e.getClass().getSimpleName());
            }
        }
        return chased;
    }

    public RunResult runOrganization(UUID organizationId) {
        RunResult result = lockTx.execute(status -> {
            Boolean acquired = jdbc.sql("select pg_try_advisory_xact_lock(hashtextextended(:k, 0))")
                    .param("k", "chasing-org:" + organizationId).query(Boolean.class).single();
            // Not acquired: another tick/instance is running this organization right now; the next tick catches up.
            return Boolean.TRUE.equals(acquired) ? runLocked(organizationId) : RunResult.SKIPPED;
        });
        return result == null ? RunResult.SKIPPED : result;
    }

    private RunResult runLocked(UUID organizationId) {
        Prep prep = newTx.execute(status -> prepare(organizationId));
        if (prep == null) {
            return RunResult.SKIPPED;
        }
        if (prep.early() != null) {
            return prep.early();
        }
        ChasingSettings settings = prep.settings();
        LocalDate today = prep.today();
        Instant now = prep.now();
        Set<String> emailedToday = prep.emailedToday();

        List<Map<String, Object>> exhaustedItems = new ArrayList<>();
        List<UUID> exhaustedIds = new ArrayList<>();
        List<Candidate> toChase = new ArrayList<>();
        int budget = Math.max(0, dailyCap - prep.usedToday());
        int skippedSameDay = 0;
        int skippedSuppressed = 0;
        int skippedOverCap = 0;
        int skippedRecipientCap = 0;
        for (Candidate c : prep.candidates()) {
            if (c.paused() || c.email() == null || c.email().isBlank() || !isDue(c, settings, today)) {
                continue;
            }
            if (c.lastChasedAt() != null && c.lastChasedAt().isAfter(now.minus(MIN_SPACING))) {
                continue; // L2: chased within the last 20 hours
            }
            if (c.attempts() >= settings.maxAttempts()) {
                if (c.exhaustedNotifiedAt() == null) {
                    exhaustedIds.add(c.vendorId());
                    exhaustedItems.add(item(c, "attempts", c.attempts()));
                }
                continue;
            }
            String hash = EmailSuppressionService.hash(c.email());
            if (prep.suppressed().contains(hash)) {
                skippedSuppressed++; // unsubscribed (here or at another organization): never emailed
                continue;
            }
            if (prep.recentlyChased().contains(hash)) {
                skippedRecipientCap++; // any organization chased this address in the last 24 h
                continue;
            }
            String recipient = c.email().trim().toLowerCase(Locale.ROOT);
            if (emailedToday.contains(recipient)) {
                skippedSameDay++; // no attempt consumed: tried again tomorrow
                continue;
            }
            if (toChase.size() >= budget) {
                skippedOverCap++;
                continue;
            }
            emailedToday.add(recipient);
            toChase.add(c);
        }
        if (skippedOverCap > 0) {
            log.warn("Chasing daily cap reached: organizationId={} cap={} deferred={}", organizationId, dailyCap,
                    skippedOverCap);
        }

        Ctx ctx = new Ctx(organizationId, prep.org().name(), settings, today, now,
                prep.staff().isEmpty() ? null : prep.staff().get(0).email());
        Tally total = new Tally();
        for (int from = 0; from < toChase.size(); from += batchSize) {
            processBatch(ctx, toChase.subList(from, Math.min(toChase.size(), from + batchSize)), total);
        }

        if ((settings.ccStaff() && !total.chasedItems.isEmpty()) || !exhaustedItems.isEmpty()) {
            newTx.executeWithoutResult(status -> {
                if (settings.ccStaff() && !total.chasedItems.isEmpty()) {
                    notifyStaff(organizationId, prep.org().name(), prep.staff(), "SUMMARY", total.chasedItems,
                            "chasesum:" + today);
                }
                if (!exhaustedItems.isEmpty()) {
                    notifyStaff(organizationId, prep.org().name(), prep.staff(), "EXHAUSTED", exhaustedItems,
                            "chaseexh:" + today);
                    store.markExhaustedNotified(organizationId, exhaustedIds, now);
                }
            });
        }
        log.info("Chasing run: organizationId={} chased={} skippedSameDay={} suppressed={} overCap={} recipientCap={} "
                        + "paused={} failed={} exhausted={}", organizationId, total.chasedItems.size(), skippedSameDay,
                skippedSuppressed + total.skippedSuppressed, skippedOverCap, skippedRecipientCap + total.skippedRecipientCap,
                total.skippedPaused, total.failed, exhaustedItems.size());
        return new RunResult(true, total.chasedItems.size(), skippedSameDay, exhaustedItems.size(),
                skippedSuppressed + total.skippedSuppressed, skippedOverCap, skippedRecipientCap + total.skippedRecipientCap,
                total.skippedPaused, total.failed, null);
    }

    /** Phase 1: reads (and the episode reset) in one short transaction. */
    private Prep prepare(UUID organizationId) {
        Optional<OrgRow> found = jdbc.sql("select name, time_zone from organization where id = :id")
                .param("id", organizationId)
                .query((rs, i) -> new OrgRow(rs.getString("name"), rs.getString("time_zone"))).optional();
        if (found.isEmpty()) {
            return null;
        }
        ChasingSettings settings = settingsStore.findOrDefault(organizationId);
        if (!settings.enabled() || subscriptions.isReadOnly(organizationId)) {
            return null;
        }
        OrgRow org = found.get();
        ZoneId zone = ZoneId.of(org.timeZone());
        LocalDateTime localNow = contexts.localNow(org.timeZone());
        if (localNow.getHour() < settings.sendHourLocal()) {
            return null;
        }
        // M4: a commercial-looking email must carry the sender's postal address. The simpler safe rule: no configured
        // address, no chases (logged once per process, not per tick).
        if (postalAddress.isBlank()) {
            if (postalWarned.compareAndSet(false, true)) {
                log.warn("Automated chasing is enabled but vendorflow.mail.postal-address is not configured: "
                        + "no chase will be sent until it is set");
            }
            return Prep.early(RunResult.blocked("postal-address-missing"));
        }
        List<StaffRecipient> staff = memberships.staffRecipients(organizationId);
        if (staff.isEmpty()) {
            // The chase's reply-to is a verified staff address; without one a vendor's reply would go nowhere.
            log.warn("Chasing not sent, no verified owner/admin to reply to: organizationId={}", organizationId);
            return Prep.early(RunResult.blocked("no-staff-contact"));
        }
        LocalDate today = localNow.toLocalDate();
        Instant now = clock.instant();

        store.resetResolvedEpisodes(organizationId, today, settings.leadDays(), now);
        List<Candidate> candidates = store.findDeficientVendors(organizationId, today, settings.leadDays());
        // One email per recipient per local day, across chases and manual document requests (and portal-link emails).
        Set<String> emailedToday = new HashSet<>(outbox.recipientsNotifiedBetween(organizationId,
                List.of(NotificationKind.DOCUMENT_REQUEST, NotificationKind.VENDOR_CHASE),
                today.atStartOfDay(zone).toInstant(), today.plusDays(1).atStartOfDay(zone).toInstant()));
        List<String> hashes = candidates.stream().filter(c -> c.email() != null && !c.email().isBlank())
                .map(c -> EmailSuppressionService.hash(c.email())).distinct().toList();
        Set<String> suppressed = suppression.suppressedAmong(hashes);
        Set<String> recentlyChased = store.recipientsChasedSince(hashes, now.minus(RECIPIENT_CAP_WINDOW));
        return new Prep(null, org, settings, today, now, candidates, emailedToday, suppressed, recentlyChased,
                store.countChasesOn(organizationId, today), staff);
    }

    /** Phase 3: one transaction per batch; on failure the batch's vendors are retried one by one. */
    private void processBatch(Ctx ctx, List<Candidate> batch, Tally total) {
        try {
            Tally t = newTx.execute(status -> runBatch(ctx, batch));
            if (t != null) {
                total.add(t);
            }
        } catch (RuntimeException e) {
            if (batch.size() == 1) {
                recordFailure(ctx.organizationId(), batch.get(0).vendorId(), e);
                total.failed++;
                return;
            }
            log.warn("Chasing batch failed, retrying its vendors one by one: organizationId={} size={} error={}",
                    ctx.organizationId(), batch.size(), e.getClass().getSimpleName());
            for (Candidate c : batch) {
                processBatch(ctx, List.of(c), total);
            }
        }
    }

    private void recordFailure(UUID organizationId, UUID vendorId, RuntimeException e) {
        // L4: stable message + counter. Id and exception class only: no vendor data, no message text.
        log.warn(FAILURE_LOG, organizationId, vendorId, e.getClass().getSimpleName());
        meters.counter(FAILURE_METRIC).increment();
    }

    private Tally runBatch(Ctx ctx, List<Candidate> batch) {
        Tally t = new Tally();
        List<UUID> superseded = new ArrayList<>();
        for (Candidate c : batch) {
            chaseVendor(ctx, c, t, superseded);
        }
        portalLinks.revokeSystemLinks(ctx.organizationId(), superseded);
        return t;
    }

    private void chaseVendor(Ctx ctx, Candidate c, Tally t, List<UUID> superseded) {
        // L1: the state may have changed since phase 1. The row lock also makes a concurrent pause/opt-out wait.
        ChasingStore.Lock lock = store.lockState(ctx.organizationId(), c.vendorId(), ctx.now());
        if (lock.paused()) {
            t.skippedPaused++;
            return;
        }
        // The vendor row is now share-locked: archived/deleted or re-addressed since phase 1 means this candidate is stale
        // (the next tick re-evaluates it with the fresh data).
        if (!"ACTIVE".equals(lock.vendorStatus()) || lock.vendorEmail() == null
                || !EmailSuppressionService.hash(lock.vendorEmail()).equals(EmailSuppressionService.hash(c.email()))) {
            return;
        }
        // Suppression and the cross-organization cap were checked in bulk in phase 1; a suppression that lands after that is
        // caught by the dispatcher backstop, and a racing chase of the same address by the unique (address, UTC day) index.
        if (chase(ctx, c, EmailSuppressionService.hash(c.email()), superseded)) {
            t.chasedItems.add(item(c, "types", c.deficiencies().stream().map(Deficiency::name).toList()));
        }
    }

    /** No chase yet, or at least {@code cadenceDays} org-local days since the last one. */
    private static boolean isDue(Candidate c, ChasingSettings settings, LocalDate today) {
        return c.lastChasedLocalDate() == null || !c.lastChasedLocalDate().plusDays(settings.cadenceDays()).isAfter(today);
    }

    private static Map<String, Object> item(Candidate c, String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vendorId", c.vendorId().toString());
        m.put("vendorName", c.companyName());
        m.put(key, value);
        return m;
    }

    /** @return false when the ledger claim was refused (already chased today): nothing else was done */
    private boolean chase(Ctx ctx, Candidate c, String recipientHash, List<UUID> supersededLinks) {
        UUID organizationId = ctx.organizationId();
        ChasingSettings settings = ctx.settings();
        LocalDate today = ctx.today();
        Instant now = ctx.now();
        UUID chaseId = UUID.randomUUID();
        UUID linkId = UUID.randomUUID();
        int attempt = c.attempts() + 1;
        String optOutToken = tokens.newToken();
        List<Map<String, Object>> types = new ArrayList<>();
        List<Map<String, Object>> emailTypes = new ArrayList<>();
        for (Deficiency d : c.deficiencies()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("id", d.typeId().toString());
            t.put("name", d.name());
            t.put("status", d.status());
            types.add(t);
            Map<String, Object> e = new LinkedHashMap<>(t);
            e.remove("id");
            if (d.expirationDate() != null) {
                e.put("expirationDate", d.expirationDate().toString());
            }
            emailTypes.add(e);
        }
        // Claim first: the unique (vendor, local date) makes a second tick/instance a no-op even without the org lock.
        if (store.insertChase(organizationId, chaseId, c.vendorId(), today, attempt, json.writeValueAsString(types),
                linkId, tokens.hash(optOutToken), now.plus(OPT_OUT_LIFETIME), recipientHash, now) == 0) {
            return false;
        }
        PortalLinkService.IssuedLink link = portalLinks.issueSystemLink(organizationId, c.vendorId(), linkId,
                c.deficiencies().stream().map(Deficiency::typeId).toList(), settings.cadenceDays() + LINK_GRACE_DAYS);
        if (c.lastLinkId() != null) {
            supersededLinks.add(c.lastLinkId());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationName", ctx.organizationName());
        // Lets pause/opt-out cancel the pending row and the dispatcher re-check the vendor (L1).
        payload.put("vendorId", c.vendorId().toString());
        payload.put("vendorName", c.companyName());
        if (c.contactName() != null) {
            payload.put("contactName", c.contactName());
        }
        if (ctx.replyTo() != null) {
            payload.put("replyTo", ctx.replyTo());
        }
        payload.put("types", emailTypes);
        payload.put("attempt", attempt);
        payload.put("maxAttempts", settings.maxAttempts());
        payload.put("expiresAt", link.expiresAt().toString());
        // Raw secrets: kept only until the dispatcher marks the row SENT/DEAD (it scrubs both keys). Never audited/logged.
        payload.put("token", link.token());
        payload.put("optOutToken", optOutToken);
        outbox.enqueue(NotificationKind.VENDOR_CHASE, organizationId, c.email().trim(),
                "chase:" + c.vendorId() + ":" + today, payload);

        store.recordChase(organizationId, c.vendorId(), today, linkId, now);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("attempt", attempt);
        metadata.put("chaseId", chaseId.toString());
        metadata.put("linkId", linkId.toString());
        if (c.lastLinkId() != null) {
            metadata.put("supersededLinkId", c.lastLinkId().toString());
        }
        metadata.put("documentTypeIds", types.stream().map(t -> t.get("id")).toList());
        audit.record(organizationId, null, "vendor.chased", "vendor", c.vendorId(), metadata);
        return true;
    }

    /** One email per staff recipient listing the vendors; names only, never a link or a token. */
    private void notifyStaff(UUID organizationId, String organizationName, List<StaffRecipient> staff, String event,
            List<Map<String, Object>> items, String keyPart) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationName", organizationName);
        payload.put("event", event);
        payload.put("items", items);
        for (StaffRecipient r : staff) {
            outbox.enqueue(NotificationKind.CHASING_STAFF_NOTICE, organizationId, r.email(),
                    keyPart + ":" + organizationId + ":" + r.userId(), payload);
        }
    }
}
