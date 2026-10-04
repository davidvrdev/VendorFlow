package com.vendorflow.chasing.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Body of {@code PUT /organization/chasing}: a full replace, so every field is required (no implicit defaults). */
public record UpdateChasingSettingsRequest(
        @NotNull Boolean enabled,
        @NotNull @Min(3) @Max(30) Integer cadenceDays,
        @NotNull @Min(1) @Max(10) Integer maxAttempts,
        @NotNull @Min(7) @Max(90) Integer leadDays,
        @NotNull @Min(0) @Max(23) Integer sendHourLocal,
        @NotNull Boolean ccStaff) {
}
