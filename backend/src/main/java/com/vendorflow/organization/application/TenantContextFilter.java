package com.vendorflow.organization.application;

import com.vendorflow.organization.domain.OrganizationSummary;
import com.vendorflow.organization.infrastructure.MembershipRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs after the Spring Security filter chain (default servlet filter order is later than the chain's), so the
 * SecurityContext is already populated. For an authenticated request it takes the active organization id from the
 * session and re-verifies the membership and role in the database on EVERY request (one query): role changes and
 * removals apply immediately and a role is never cached in the session. If the membership is gone the session
 * attribute is dropped and the TenantContext stays empty (tenant endpoints then answer 403 "No active organization").
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class TenantContextFilter extends OncePerRequestFilter {

    private final MembershipRepository memberships;
    private final TenantContext tenantContext;

    public TenantContextFilter(MembershipRepository memberships, TenantContext tenantContext) {
        this.memberships = memberships;
        this.tenantContext = tenantContext;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            resolve(request);
            chain.doFilter(request, response);
        } finally {
            tenantContext.clear();
            MDC.remove("userId");
            MDC.remove("orgId");
        }
    }

    private void resolve(HttpServletRequest request) {
        Optional<UUID> userId = currentUserId();
        if (userId.isEmpty()) {
            return;
        }
        MDC.put("userId", userId.get().toString());
        HttpSession session = request.getSession(false);
        Optional<UUID> activeOrgId = ActiveOrganizationSession.get(session);
        if (activeOrgId.isEmpty()) {
            return;
        }
        Optional<OrganizationSummary> membership = memberships.findSummary(userId.get(), activeOrgId.get());
        if (membership.isPresent()) {
            tenantContext.set(new TenantContext.Tenant(activeOrgId.get(), userId.get(), membership.get().role()));
            MDC.put("orgId", activeOrgId.get().toString());
        } else {
            ActiveOrganizationSession.clear(session);
        }
    }

    private static Optional<UUID> currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(auth.getName()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
