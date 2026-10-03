package com.vendorflow.vendor.api;

import com.vendorflow.vendor.domain.VendorStatus;
import java.time.Instant;
import java.util.UUID;

/** API shape "VendorSummary" and the JPQL constructor projection of the list query (count comes from a subquery). */
public record VendorSummary(UUID id, String companyName, String contactName, String email, String phone,
        String category, VendorStatus status, long requirementCount, Instant createdAt, Instant updatedAt) {
}
