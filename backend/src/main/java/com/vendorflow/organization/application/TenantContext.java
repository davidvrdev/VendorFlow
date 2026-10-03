package com.vendorflow.organization.application;

import com.vendorflow.organization.domain.Role;
import com.vendorflow.shared.error.NoActiveOrganizationException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The request's tenant: organization, user and role, as verified against the database by TenantContextFilter.
 * Organization ids used for data access come ONLY from here, never from request input (ADR-0004).
 * Thread-bound; the filter always clears it in a finally block.
 */
@Component
public class TenantContext {

    public record Tenant(UUID organizationId, UUID userId, Role role) {
    }

    private static final ThreadLocal<Tenant> CURRENT = new ThreadLocal<>();

    public void set(Tenant tenant) {
        CURRENT.set(tenant);
    }

    public void clear() {
        CURRENT.remove();
    }

    public Optional<Tenant> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** For tenant endpoints/services: 403 "No active organization" when there is none. */
    public Tenant require() {
        Tenant tenant = CURRENT.get();
        if (tenant == null) {
            throw new NoActiveOrganizationException();
        }
        return tenant;
    }
}
