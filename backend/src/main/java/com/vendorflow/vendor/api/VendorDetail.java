package com.vendorflow.vendor.api;

import com.vendorflow.compliance.domain.ComplianceSummary;
import com.vendorflow.compliance.domain.RequirementStatus;
import com.vendorflow.document.api.DocumentSummary;
import com.vendorflow.vendor.domain.VendorStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API shape "VendorDetail" = VendorSummary + notes, createdBy, requirements (by sortOrder; each with its CURRENT
 * document or null) and otherDocuments (CURRENT documents whose type is not a requirement of the vendor).
 */
public record VendorDetail(UUID id, String companyName, String contactName, String email, String phone,
        String category, VendorStatus status, long requirementCount, Instant createdAt, Instant updatedAt,
        String notes, CreatedBy createdBy, List<Requirement> requirements, List<DocumentSummary> otherDocuments,
        ComplianceSummary compliance) {

    public record CreatedBy(String fullName) {
    }

    public record Requirement(UUID documentTypeId, String code, String name, boolean hasExpiration,
            DocumentSummary currentDocument, RequirementStatus status, Integer daysUntilExpiration, boolean active) {
    }
}
