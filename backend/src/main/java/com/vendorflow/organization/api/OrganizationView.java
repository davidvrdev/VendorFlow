package com.vendorflow.organization.api;

import com.vendorflow.organization.domain.Organization;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** API shape "Organization" from docs/API.md. */
public record OrganizationView(UUID id, String name, String timeZone, int expiringWindowDays,
        List<Integer> reminderOffsetsDays, boolean remindersEnabled, Instant createdAt) {

    public static OrganizationView from(Organization o) {
        return new OrganizationView(o.getId(), o.getName(), o.getTimeZone(), o.getExpiringWindowDays(),
                Arrays.stream(o.getReminderOffsetsDays()).boxed().toList(), o.isRemindersEnabled(),
                o.getCreatedAt());
    }
}
