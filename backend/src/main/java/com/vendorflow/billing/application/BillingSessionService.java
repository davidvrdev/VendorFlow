package com.vendorflow.billing.application;

import com.vendorflow.audit.AuditService;
import com.vendorflow.billing.api.SessionUrlView;
import com.vendorflow.billing.domain.Subscription;
import com.vendorflow.billing.domain.SubscriptionStatus;
import com.vendorflow.billing.infrastructure.SubscriptionRepository;
import com.vendorflow.organization.application.AuthorizationService;
import com.vendorflow.organization.application.OrganizationService;
import com.vendorflow.shared.tenant.TenantContext;
import com.vendorflow.organization.domain.Permission;
import com.vendorflow.shared.error.ApiException;
import com.vendorflow.shared.error.NotFoundException;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stripe Checkout / Customer Portal sessions for the caller's organization (OWNER only). Deliberately NOT
 * {@code @Transactional}: the Stripe HTTP calls must not run inside a database transaction, so each database step
 * uses its own short TransactionTemplate.
 */
@Service
public class BillingSessionService {

    private static final Logger log = LoggerFactory.getLogger(BillingSessionService.class);

    private final SubscriptionRepository subscriptions;
    private final BillingGateway gateway;
    private final BillingProperties properties;
    private final AuthorizationService authorization;
    private final OrganizationService organizations;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final String baseUrl;

    public BillingSessionService(SubscriptionRepository subscriptions, BillingGateway gateway,
            BillingProperties properties, AuthorizationService authorization, OrganizationService organizations,
            AuditService audit, PlatformTransactionManager transactionManager, Clock clock,
            @Value("${app.base-url}") String baseUrl) {
        this.subscriptions = subscriptions;
        this.gateway = gateway;
        this.properties = properties;
        this.authorization = authorization;
        this.organizations = organizations;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public SessionUrlView createCheckoutSession() {
        TenantContext.Tenant tenant = authorization.require(Permission.BILLING_MANAGE);
        requireConfigured();
        Subscription current = tx.execute(s -> load(tenant.organizationId()));
        // Already paying (or paying but late): changes go through the portal, a second subscription would double-bill.
        if (current.getStripeSubscriptionId() != null && (current.getStatus() == SubscriptionStatus.ACTIVE
                || current.getStatus() == SubscriptionStatus.PAST_DUE
                || current.getStatus() == SubscriptionStatus.TRIALING)) {
            throw new ApiException(HttpStatus.CONFLICT, "already-subscribed", "Already subscribed",
                    "This organization already has a subscription. Use the billing portal to manage it.");
        }
        String customerId = ensureCustomer(tenant);
        String url = callGateway(() -> gateway.createCheckoutSession(customerId, tenant.organizationId(),
                properties.getStripe().getPriceId(), baseUrl + "/settings/billing?checkout=success",
                baseUrl + "/settings/billing?checkout=canceled"));
        tx.executeWithoutResult(s -> audit.record(tenant.organizationId(), tenant.userId(),
                "billing.checkout.started", "subscription", current.getId(), Map.of()));
        return new SessionUrlView(url);
    }

    public SessionUrlView createPortalSession() {
        TenantContext.Tenant tenant = authorization.require(Permission.BILLING_MANAGE);
        requireConfigured();
        Subscription current = tx.execute(s -> load(tenant.organizationId()));
        String customerId = ensureCustomer(tenant);
        String url = callGateway(() -> gateway.createPortalSession(customerId, baseUrl + "/settings/billing"));
        tx.executeWithoutResult(s -> audit.record(tenant.organizationId(), tenant.userId(),
                "billing.portal.opened", "subscription", current.getId(), Map.of()));
        return new SessionUrlView(url);
    }

    private void requireConfigured() {
        if (!properties.apiConfigured()) {
            throw new BillingNotConfiguredException();
        }
    }

    private Subscription load(UUID organizationId) {
        return subscriptions.findByOrganizationId(organizationId)
                .orElseThrow(() -> new NotFoundException("Subscription not found."));
    }

    /** Lazily creates the Stripe customer (idempotency key per organization) and stores its id once. */
    private String ensureCustomer(TenantContext.Tenant tenant) {
        UUID orgId = tenant.organizationId();
        String existing = tx.execute(s -> load(orgId).getStripeCustomerId());
        if (existing != null) {
            return existing;
        }
        String name = organizations.nameOf(orgId);
        String created = callGateway(() -> gateway.createCustomer(orgId, name));
        return tx.execute(s -> {
            // Row lock: two concurrent first-time checkouts store one id (the first writer's).
            Subscription row = subscriptions.findByOrganizationIdForUpdate(orgId)
                    .orElseThrow(() -> new NotFoundException("Subscription not found."));
            if (row.getStripeCustomerId() != null) {
                return row.getStripeCustomerId();
            }
            row.attachCustomer(created, clock.instant());
            audit.record(orgId, tenant.userId(), "billing.customer.created", "subscription", row.getId(), Map.of());
            return created;
        });
    }

    private <T> T callGateway(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (BillingGatewayException e) {
            // Class name only: the cause may mention request details.
            log.error("Billing provider call failed: error={}", e.getCause() == null
                    ? e.getClass().getSimpleName() : e.getCause().getClass().getSimpleName());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "billing-provider-error", "Billing provider unavailable",
                    "The billing provider could not be reached. Please try again later.");
        }
    }
}
