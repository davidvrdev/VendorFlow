package com.vendorflow.vendor.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Full replacement of a vendor's required document types. Duplicates are ignored; empty is allowed. */
public record RequirementsRequest(@NotNull @Size(max = 200) List<@NotNull UUID> documentTypeIds) {
}
