package com.vendorflow.vendor.api;

import com.vendorflow.compliance.domain.ComplianceSummary;
import com.vendorflow.vendor.domain.VendorStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * API shape "VendorSummary". {@code requirementCount} counts ACTIVE requirements only (requirements on deactivated
 * document types are ignored by compliance); {@code compliance} comes from the same single list query.
 */
public record VendorSummary(UUID id, String companyName, String contactName, String email, String phone,
        String category, VendorStatus status, long requirementCount, Instant createdAt, Instant updatedAt,
        ComplianceSummary compliance) {
}
