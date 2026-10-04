package com.vendorflow.portal.api;

import com.vendorflow.compliance.domain.RequirementStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What a vendor sees through a portal link, and NOTHING else: organization display name, vendor name and the
 * requested types with their status. No ids of the organization or vendor, no other tenant data.
 */
public record PortalInfo(String organizationName, String vendorName, Instant expiresAt, int remainingUploads,
        boolean acceptingUploads, List<RequestedType> documentTypes) {

    public record RequestedType(UUID id, String name, boolean hasExpiration, RequirementStatus status,
            LocalDate expirationDate) {
    }
}
