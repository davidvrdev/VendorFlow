package com.vendorflow.vendor.api;

import com.vendorflow.vendor.domain.VendorStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** API shape "VendorDetail" = VendorSummary + notes, createdBy, requirements (by sortOrder). */
public record VendorDetail(UUID id, String companyName, String contactName, String email, String phone,
        String category, VendorStatus status, long requirementCount, Instant createdAt, Instant updatedAt,
        String notes, CreatedBy createdBy, List<Requirement> requirements) {

    public record CreatedBy(String fullName) {
    }

    public record Requirement(UUID documentTypeId, String code, String name, boolean hasExpiration) {
    }
}
