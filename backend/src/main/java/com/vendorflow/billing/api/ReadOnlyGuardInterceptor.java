package com.vendorflow.billing.api;

import com.vendorflow.billing.application.BillingProperties;
import com.vendorflow.billing.application.SubscriptionService;
import com.vendorflow.shared.tenant.TenantContext;
import com.vendorflow.shared.error.Problems;
import com.vendorflow.shared.security.ProblemJsonWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.UrlPathHelper;

/**
 * THE read-only enforcement point (Phase 8): while the organization's subscription is inactive every mutating request
 * (POST/PUT/PATCH/DELETE) answers 402 "Subscription inactive". Reads, downloads and CSV export are GETs and stay open.
 *
 * <p>It is an MVC interceptor so it runs after the security chain and TenantContextFilter (the tenant is known) and
 * covers every present and future tenant endpoint without per-endpoint code. Paths that must keep working while
 * inactive are excluded where it is registered ({@link BillingWebConfig}: auth, billing, webhooks, session
 * switching, invitation acceptance) plus members removal below. Requests without a tenant pass: they have nothing to
 * protect here and the services answer 401/403 themselves.
 */
@Component
public class ReadOnlyGuardInterceptor implements HandlerInterceptor {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final BillingProperties properties;
    private final SubscriptionService subscriptions;
    private final TenantContext tenantContext;
    private final ProblemJsonWriter writer;

    public ReadOnlyGuardInterceptor(BillingProperties properties, SubscriptionService subscriptions,
            TenantContext tenantContext, ProblemJsonWriter writer) {
        this.properties = properties;
        this.subscriptions = subscriptions;
        this.tenantContext = tenantContext;
        this.writer = writer;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!properties.isEnabled() || SAFE_METHODS.contains(request.getMethod())) {
            return true;
        }
        var tenant = tenantContext.current();
        if (tenant.isEmpty()) {
            return true;
        }
        // Leaving / removing members stays possible when the subscription lapsed (offboarding is not content growth).
        if ("DELETE".equals(request.getMethod()) && MATCHER.match("/api/v1/organization/members/*",
                UrlPathHelper.defaultInstance.getPathWithinApplication(request))) {
            return true;
        }
        if (!subscriptions.isReadOnly(tenant.get().organizationId())) {
            return true;
        }
        writer.write(response, Problems.of(HttpStatus.PAYMENT_REQUIRED, "subscription-inactive",
                "Subscription inactive",
                "The subscription of this organization is inactive, so it is read-only. Renew it in Billing."),
                request.getRequestURI());
        return false;
    }
}
