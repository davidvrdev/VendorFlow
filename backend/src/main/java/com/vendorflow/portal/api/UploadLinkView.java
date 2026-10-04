package com.vendorflow.portal.api;

import com.vendorflow.portal.domain.LinkStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** API shape "UploadLink" (docs/API.md Phase 14). Never contains the token or its hash. */
public record UploadLinkView(UUID id, UUID vendorId, List<TypeRef> documentTypes, LinkStatus status,
        CreatedBy createdBy, Instant createdAt, Instant expiresAt, Instant revokedAt, Instant lastUsedAt,
        int useCount, int maxUploads, long maxTotalBytes, long usedBytes) {

    public record TypeRef(UUID id, String name) {
    }

    public record CreatedBy(String fullName) {
    }
}
