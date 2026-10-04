package com.vendorflow.chasing.application;

import com.vendorflow.audit.AuditService;
import com.vendorflow.chasing.api.ChasingSettingsView;
import com.vendorflow.chasing.api.UpdateChasingSettingsRequest;
import com.vendorflow.chasing.infrastructure.ChasingSettingsStore;
import com.vendorflow.identity.application.UserAccountService;
import com.vendorflow.organization.application.AuthorizationService;
import com.vendorflow.organization.domain.Permission;
import com.vendorflow.shared.error.ApiException;
import com.vendorflow.shared.tenant.Role;
import com.vendorflow.shared.tenant.TenantContext;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Organization-level chasing settings (ORG_SETTINGS_MANAGE to change, any member to read). */
@Service
public class ChasingSettingsService {

    private final AuthorizationService authorization;
    private final ChasingSettingsStore store;
    private final UserAccountService users;
    private final AuditService audit;
    private final Clock clock;

    public ChasingSettingsService(AuthorizationService authorization, ChasingSettingsStore store, AuditService audit,
            UserAccountService users, Clock clock) {
        this.authorization = authorization;
        this.store = store;
        this.audit = audit;
        this.users = users;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ChasingSettingsView get() {
        TenantContext.Tenant tenant = authorization.require(Permission.DATA_VIEW);
        return ChasingSettingsView.from(store.findOrDefault(tenant.organizationId()));
    }

    @Transactional
    public ChasingSettingsView update(UpdateChasingSettingsRequest request) {
        TenantContext.Tenant tenant = authorization.require(Permission.ORG_SETTINGS_MANAGE);
        UUID orgId = tenant.organizationId();
        ChasingSettings before = store.findOrDefault(orgId);
        boolean enabling = request.enabled() && !before.enabled();
        if (enabling) {
            requireVerifiedStaff(tenant);
        }
        ChasingSettings after = new ChasingSettings(request.enabled(), request.cadenceDays(), request.maxAttempts(),
                request.leadDays(), request.sendHourLocal(), request.ccStaff());
        if (!before.equals(after) || store.find(orgId).isEmpty()) {
            store.upsert(orgId, after, clock.instant());
        }
        if (!before.equals(after)) {
            Map<String, Object> changes = new LinkedHashMap<>();
            change(changes, "enabled", before.enabled(), after.enabled());
            change(changes, "cadenceDays", before.cadenceDays(), after.cadenceDays());
            change(changes, "maxAttempts", before.maxAttempts(), after.maxAttempts());
            change(changes, "leadDays", before.leadDays(), after.leadDays());
            change(changes, "sendHourLocal", before.sendHourLocal(), after.sendHourLocal());
            change(changes, "ccStaff", before.ccStaff(), after.ccStaff());
            audit.record("chasing.settings.updated", "organization", orgId, Map.of("changes", changes));
        }
        return ChasingSettingsView.from(after);
    }

    /** Chasing emails third parties on the organization's behalf: only a verified OWNER/ADMIN may switch it on. */
    private void requireVerifiedStaff(TenantContext.Tenant tenant) {
        if (tenant.role() != Role.OWNER && tenant.role() != Role.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Only an owner or admin can turn on automatic reminders.");
        }
        if (!users.require(tenant.userId()).emailVerified()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "email-not-verified", "Email not verified",
                    "Verify your email address before turning on automatic reminders to vendors.");
        }
    }

    private static void change(Map<String, Object> changes, String field, Object before, Object after) {
        if (!before.equals(after)) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("before", before);
            c.put("after", after);
            changes.put(field, c);
        }
    }
}
