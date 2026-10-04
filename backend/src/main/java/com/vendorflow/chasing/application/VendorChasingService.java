package com.vendorflow.chasing.application;

import com.vendorflow.audit.AuditService;
import com.vendorflow.chasing.api.ChaseView;
import com.vendorflow.chasing.api.VendorChasingStateView;
import com.vendorflow.chasing.infrastructure.ChasingSettingsStore;
import com.vendorflow.chasing.infrastructure.ChasingStore;
import com.vendorflow.compliance.application.ComplianceContextService;
import com.vendorflow.notification.OutboxService;
import com.vendorflow.organization.application.AuthorizationService;
import com.vendorflow.organization.application.OrganizationService;
import com.vendorflow.organization.domain.Permission;
import com.vendorflow.portal.application.PortalLinkService;
import com.vendorflow.portal.domain.LinkStatus;
import com.vendorflow.shared.error.ApiException;
import com.vendorflow.shared.error.NotFoundException;
import com.vendorflow.shared.tenant.TenantContext;
import com.vendorflow.shared.web.PageResponse;
import com.vendorflow.vendor.application.VendorService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** Staff side of per-vendor chasing: state, pause/resume and the activity list (docs/API.md Phase 15). */
@Service
public class VendorChasingService {

    private final AuthorizationService authorization;
    private final VendorService vendors;
    private final ChasingSettingsStore settingsStore;
    private final ChasingStore store;
    private final ComplianceContextService contexts;
    private final OrganizationService organizations;
    private final PortalLinkService portalLinks;
    private final OutboxService outbox;
    private final AuditService audit;
    private final Clock clock;

    public VendorChasingService(AuthorizationService authorization, VendorService vendors,
            ChasingSettingsStore settingsStore, ChasingStore store, ComplianceContextService contexts,
            OrganizationService organizations, PortalLinkService portalLinks, OutboxService outbox, AuditService audit, Clock clock) {
        this.authorization = authorization;
        this.vendors = vendors;
        this.settingsStore = settingsStore;
        this.store = store;
        this.contexts = contexts;
        this.organizations = organizations;
        this.portalLinks = portalLinks;
        this.outbox = outbox;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public VendorChasingStateView state(UUID vendorId) {
        TenantContext.Tenant tenant = authorization.require(Permission.DATA_VIEW);
        VendorService.VendorBrief vendor = vendors.findBrief(tenant.organizationId(), vendorId)
                .orElseThrow(() -> new NotFoundException("Vendor not found."));
        return compute(tenant.organizationId(), vendor);
    }

    @Transactional
    public VendorChasingStateView setPaused(UUID vendorId, boolean paused) {
        TenantContext.Tenant tenant = authorization.require(Permission.CONTENT_WRITE);
        UUID orgId = tenant.organizationId();
        VendorService.VendorBrief vendor = vendors.findBrief(orgId, vendorId)
                .orElseThrow(() -> new NotFoundException("Vendor not found."));
        Instant now = clock.instant();
        ChasingStore.PauseOutcome outcome = paused ? store.pause(orgId, vendorId, now)
                : store.resume(orgId, vendorId, now);
        if (outcome == ChasingStore.PauseOutcome.OPTED_OUT) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "vendor-opted-out", "Vendor opted out",
                    "This vendor unsubscribed from automatic reminders and cannot be resumed.");
        }
        if (outcome == ChasingStore.PauseOutcome.CHANGED) {
            if (paused) {
                // L1: a chase already queued for this vendor must not go out after the pause (tokens scrubbed too).
                outbox.cancelPendingVendorChases(orgId, vendorId, "Vendor chasing paused");
            }
            audit.record(paused ? "vendor.chasing.paused" : "vendor.chasing.resumed", VendorService.ENTITY_TYPE,
                    vendorId, Map.of());
        }
        return compute(orgId, vendor);
    }

    @Transactional(readOnly = true)
    public PageResponse<ChaseView> chases(UUID vendorId, int page, int size) {
        TenantContext.Tenant tenant = authorization.require(Permission.DATA_VIEW);
        UUID orgId = tenant.organizationId();
        vendors.requireExists(orgId, vendorId);
        Pageable pageable = PageResponse.pageable(page, size);
        long total = store.countChases(orgId, vendorId);
        List<ChasingStore.ChaseRow> rows = store.findChases(orgId, vendorId, pageable.getPageSize(),
                pageable.getOffset());
        Map<UUID, LinkStatus> linkStatuses = portalLinks.statuses(orgId,
                rows.stream().map(ChasingStore.ChaseRow::linkId).toList());
        List<ChaseView> items = new ArrayList<>();
        for (ChasingStore.ChaseRow r : rows) {
            List<ChaseView.TypeRef> types = new ArrayList<>();
            for (JsonNode t : r.types()) {
                types.add(new ChaseView.TypeRef(UUID.fromString(t.get("id").asString()), t.get("name").asString(),
                        t.get("status").asString()));
            }
            LinkStatus link = linkStatuses.get(r.linkId());
            items.add(new ChaseView(r.id(), r.localDate(), r.attempt(), types, r.createdAt(),
                    link == null ? null : link.name(), r.emailStatus()));
        }
        int totalPages = (int) Math.ceil(total / (double) pageable.getPageSize());
        return new PageResponse<>(items, pageable.getPageNumber(), pageable.getPageSize(), total, totalPages);
    }

    private VendorChasingStateView compute(UUID orgId, VendorService.VendorBrief vendor) {
        ChasingSettings settings = settingsStore.findOrDefault(orgId);
        ChasingStore.State s = store.findState(orgId, vendor.id());
        String zoneId = organizations.complianceSettings(orgId).timeZone();
        LocalDate today = contexts.todayIn(zoneId);
        boolean hasEmail = vendor.email() != null && !vendor.email().isBlank();
        boolean deficient = vendor.active() && settings.enabled()
                && store.hasDeficiency(orgId, vendor.id(), today, settings.leadDays());
        int attempts = deficient ? s.attempts() : 0;

        VendorChasingStateView.Status status;
        Instant next = null;
        if (s.paused()) {
            status = VendorChasingStateView.Status.PAUSED;
        } else if (!hasEmail) {
            status = VendorChasingStateView.Status.NO_EMAIL;
        } else if (!deficient) {
            status = VendorChasingStateView.Status.IDLE;
        } else if (attempts >= settings.maxAttempts()) {
            status = VendorChasingStateView.Status.EXHAUSTED;
        } else {
            status = VendorChasingStateView.Status.ACTIVE;
            LocalDate nextDate = s.lastChasedLocalDate() == null ? today
                    : max(today, s.lastChasedLocalDate().plusDays(settings.cadenceDays()));
            Instant at = nextDate.atTime(settings.sendHourLocal(), 0).atZone(ZoneId.of(zoneId)).toInstant();
            Instant now = clock.instant();
            next = at.isBefore(now) ? now : at;
        }
        return new VendorChasingStateView(s.paused(), s.pausedReason(), s.lastChasedAt(), attempts,
                settings.maxAttempts(), next, status);
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }
}
