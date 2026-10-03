package com.vendorflow.document.api;

import com.vendorflow.shared.validation.PlainText;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Body of PATCH /document-types/{id}: every field optional, absent (or null) = unchanged. {@code code} is not
 * accepted (immutable); unknown JSON properties are ignored.
 */
public record DocumentTypeUpdateRequest(
        @Size(min = 1, max = 100) @PlainText String name,
        Boolean hasExpiration,
        Boolean requiredByDefault,
        Boolean active,
        @Min(0) @Max(100000) Integer sortOrder) {

    public DocumentTypeUpdateRequest {
        name = name == null ? null : name.strip();
    }
}
