package com.vendorflow.billing.application;

import com.vendorflow.audit.AuditService;
import com.vendorflow.billing.api.SubscriptionView;
import com.vendorflow.billing.domain.Subscription;
import com.vendorflow.billing.domain.SubscriptionAccess;
import com.vendorflow.billing.infrastructure.SubscriptionRepository;
import com.vendorflow.organization.application.AuthorizationService;
import com.vendorflow.organization.application.TenantContext;
import com.vendorflow.organization.domain.OrganizationCreatedEvent;
import com.vendorflow.organization.domain.Permission;
import com.vendorflow.organization.domain.RolePermissions;
import com.vendorflow.shared.error.NotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Subscription state per organization: trial start, the read model, and the read-only decision. */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    private final SubscriptionRepository subscriptions;
    private final BillingProperties properties;
    private final AuthorizationService authorization;
    private final AuditService audit;
    private final Clock clock;

    public SubscriptionService(SubscriptionRepository subscriptions, BillingProperties properties,
            AuthorizationService authorization, AuditService audit, Clock clock) {
        this.subscriptions = subscriptions;
        this.properties = properties;
        this.authorization = authorization;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Synchronous listener inside the signup transaction (same pattern as the document-type seeding): an organization
     * never exists without a subscription row, and a failure here rolls the signup back. The organization feature
     * does not depend on billing; billing listens to its event.
     */
    @EventListener
    public void onOrganizationCreated(OrganizationCreatedEvent event) {
        startTrial(event.organizationId());
    }

    /** Idempotent. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void startTrial(UUID organizationId) {
        if (subscriptions.findByOrganizationId(organizationId).isPresent()) {
            return;
        }
        Instant now = clock.instant();
        Instant trialEnd = now.plus(Duration.ofDays(properties.getTrialDays()));
        Subscription saved = subscriptions.save(
                Subscription.startTrial(organizationId, properties.getPlanKey(), now, trialEnd));
        audit.record(organizationId, null, "billing.trial.started", "subscription", saved.getId(),
                Map.of("trialEndsAt", trialEnd.toString()));
    }

    /** Any member of the active organization may see the subscription state. */
    @Transactional(readOnly = true)
    public SubscriptionView current() {
        TenantContext.Tenant tenant = authorization.require(Permission.DATA_VIEW);
        Subscription s = subscriptions.findByOrganizationId(tenant.organizationId())
                .orElseThrow(() -> new NotFoundException("Subscription not found."));
        return new SubscriptionView(s.getStatus().name(), s.getPlan(), s.getTrialEndsAt(), s.getCurrentPeriodEnd(),
                s.isCancelAtPeriodEnd(), readOnly(s), RolePermissions.has(tenant.role(), Permission.BILLING_MANAGE));
    }

    /**
     * Used by the read-only guard and the scheduled jobs. Billing disabled = never read-only. An organization
     * without a subscription row (a bug: signup and the V8 backfill create one) fails closed.
     */
    @Transactional(readOnly = true)
    public boolean isReadOnly(UUID organizationId) {
        if (!properties.isEnabled()) {
            return false;
        }
        return subscriptions.findByOrganizationId(organizationId).map(this::readOnly).orElseGet(() -> {
            log.error("Organization without subscription row: organizationId={}", organizationId);
            return true;
        });
    }

    private boolean readOnly(Subscription s) {
        return properties.isEnabled()
                && SubscriptionAccess.isReadOnly(s.getStatus(), s.getTrialEndsAt(), clock.instant());
    }
}
