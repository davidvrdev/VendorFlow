package com.vendorflow.audit;

import com.vendorflow.organization.application.TenantContext;
import com.vendorflow.shared.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Records audit events in the CALLER's transaction (MANDATORY propagation): the business change and its audit row
 * commit or roll back together, and calling this without a transaction is a programming error.
 * Metadata must contain before/after values of changed fields only: never passwords, tokens or document contents.
 */
@Service
public class AuditService {

    private final AuditEventRepository repository;
    private final TenantContext tenantContext;
    private final IpHasher ipHasher;
    private final Clock clock;

    public AuditService(AuditEventRepository repository, TenantContext tenantContext, IpHasher ipHasher, Clock clock) {
        this.repository = repository;
        this.tenantContext = tenantContext;
        this.ipHasher = ipHasher;
        this.clock = clock;
    }

    /** Organization and actor are taken from the current TenantContext (if any). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String action, String entityType, UUID entityId, Map<String, Object> metadata) {
        var tenant = tenantContext.current();
        record(tenant.map(TenantContext.Tenant::organizationId).orElse(null),
                tenant.map(TenantContext.Tenant::userId).orElse(null), action, entityType, entityId, metadata);
    }

    /** For flows that run before/outside a tenant context (signup, switching organization). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID organizationId, UUID actorUserId, String action, String entityType, UUID entityId,
            Map<String, Object> metadata) {
        repository.save(new AuditEvent(organizationId, actorUserId, action, entityType, entityId,
                metadata == null ? Map.of() : metadata, MDC.get(RequestIdFilter.MDC_KEY), currentIpHash(),
                clock.instant()));
    }

    private String currentIpHash() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            HttpServletRequest request = sra.getRequest();
            String ip = request.getRemoteAddr();
            return ip == null ? null : ipHasher.hash(ip);
        }
        return null;
    }
}
