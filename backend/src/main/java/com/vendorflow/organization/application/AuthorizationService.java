package com.vendorflow.organization.application;

import com.vendorflow.organization.domain.Permission;
import com.vendorflow.organization.domain.RolePermissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/** Role to permission checks, called from application services (controllers never decide on their own). */
@Service
public class AuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationService.class);

    private final TenantContext tenantContext;

    public AuthorizationService(TenantContext tenantContext) {
        this.tenantContext = tenantContext;
    }

    /**
     * @return the verified tenant, for convenience
     * @throws com.vendorflow.shared.error.NoActiveOrganizationException (403) if there is no active organization
     * @throws AccessDeniedException (403) if the role lacks the permission
     */
    public TenantContext.Tenant require(Permission permission) {
        TenantContext.Tenant tenant = tenantContext.require();
        if (!RolePermissions.has(tenant.role(), permission)) {
            log.warn("Authorization denied: permission={} role={} user={} org={}", permission, tenant.role(),
                    tenant.userId(), tenant.organizationId());
            throw new AccessDeniedException("Missing permission " + permission);
        }
        return tenant;
    }
}
