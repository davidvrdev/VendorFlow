package com.vendorflow.portal.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Body of {@code POST /vendors/{vendorId}/upload-links}. Explicit record: nothing else can be set (mass assignment). */
public record CreateUploadLinkRequest(
        @NotEmpty @Size(max = 20) List<@NotNull UUID> documentTypeIds,
        @Min(1) @Max(30) Integer expiresInDays,
        @Min(1) @Max(50) Integer maxUploads,
        @Min(1) @Max(500) Integer maxTotalMb,
        Boolean sendEmail) {
}
