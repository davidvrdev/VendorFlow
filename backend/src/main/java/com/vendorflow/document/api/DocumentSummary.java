package com.vendorflow.document.api;

import com.vendorflow.document.domain.DocumentState;
import com.vendorflow.document.domain.ReviewStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** API shape "DocumentSummary" (docs/API.md Phase 3). Never exposes the storage key or the SHA-256. */
public record DocumentSummary(UUID id, UUID vendorId, TypeRef documentType, DocumentState state,
        ReviewStatus reviewStatus, LocalDate issueDate, LocalDate expirationDate, String originalFilename,
        String mimeType, long sizeBytes, UserRef uploadedBy, Instant uploadedAt, UserRef reviewedBy,
        Instant reviewedAt, String reviewNote) {

    public record TypeRef(UUID id, String code, String name, boolean hasExpiration) {
    }

    public record UserRef(String fullName) {
    }
}
