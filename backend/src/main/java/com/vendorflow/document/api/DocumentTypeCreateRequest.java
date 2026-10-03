package com.vendorflow.document.api;

import com.vendorflow.shared.validation.PlainText;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of POST /document-types. Code, sort order and active are server-decided (never client input). */
public record DocumentTypeCreateRequest(
        @NotBlank @Size(max = 100) @PlainText String name,
        @NotNull Boolean hasExpiration,
        @NotNull Boolean requiredByDefault) {

    public DocumentTypeCreateRequest {
        name = name == null ? null : name.strip();
    }
}
