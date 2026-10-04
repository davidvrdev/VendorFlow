package com.vendorflow.identity.application;

import com.vendorflow.shared.tenant.TenantContext;

import com.vendorflow.identity.api.Me;
import com.vendorflow.identity.domain.AppUser;
import com.vendorflow.identity.infrastructure.AppUserRepository;
import com.vendorflow.organization.application.OrganizationService;
import com.vendorflow.organization.domain.OrganizationSummary;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MeService {

    private final AppUserRepository users;
    private final OrganizationService organizations;

    public MeService(AppUserRepository users, OrganizationService organizations) {
        this.users = users;
        this.organizations = organizations;
    }

    /**
     * @param activeOrganizationId the verified active organization (from TenantContext, or just resolved at login /
     *                             switch), or null. It is looked up among the user's memberships, never trusted.
     */
    @Transactional(readOnly = true)
    public Me build(UUID userId, UUID activeOrganizationId) {
        AppUser user = users.findById(userId).orElseThrow(() -> new BadCredentialsException("Unknown user"));
        List<OrganizationSummary> mine = organizations.listForUser(userId);
        OrganizationSummary active = activeOrganizationId == null ? null
                : mine.stream().filter(o -> o.id().equals(activeOrganizationId)).findFirst().orElse(null);
        return new Me(new Me.UserView(user.getId(), user.getEmail(), user.getFullName(), user.isEmailVerified()),
                active, mine);
    }
}
