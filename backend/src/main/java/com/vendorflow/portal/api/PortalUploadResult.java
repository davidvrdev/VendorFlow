package com.vendorflow.portal.api;

import java.time.Instant;
import java.util.UUID;

/** Receipt shown to the vendor after an upload. {@code status} is always PENDING_REVIEW: vendors cannot approve. */
public record PortalUploadResult(TypeRef documentType, String originalFilename, String status, Instant uploadedAt,
        int remainingUploads) {

    public record TypeRef(UUID id, String name) {
    }
}
