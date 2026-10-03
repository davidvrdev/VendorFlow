package com.vendorflow.identity.api;

import com.vendorflow.organization.domain.OrganizationSummary;
import java.util.List;
import java.util.UUID;

/** API shape "Me" from docs/API.md. */
public record Me(UserView user, OrganizationSummary activeOrganization, List<OrganizationSummary> organizations) {

    public record UserView(UUID id, String email, String fullName, boolean emailVerified) {
    }
}
