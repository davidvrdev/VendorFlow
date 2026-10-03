package com.vendorflow.organization.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * PATCH body: every field optional, null = unchanged. Explicit record (never an entity) so clients cannot set
 * anything else, e.g. an organization id. Zone validity and offset uniqueness are checked in the service.
 */
public record UpdateOrganizationRequest(
        @Size(min = 1, max = 120) String name,
        @Size(max = 64) String timeZone,
        @Min(1) @Max(180) Integer expiringWindowDays,
        @Size(min = 1, max = 5) List<@NotNull @Min(1) @Max(180) Integer> reminderOffsetsDays,
        Boolean remindersEnabled) {
}
