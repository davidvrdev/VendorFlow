package com.vendorflow.compliance.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * API shape "ComplianceSummary": counts are over ACTIVE requirements only.
 * {@code daysUntilNextExpiration} is computed against the organization's "today" (its time zone), so clients never
 * derive day counts from their own clock (a UTC client could be off by one day).
 */
public record ComplianceSummary(VendorCompliance status, int missing, int expired, int expiring, int reviewRequired,
        int ok, LocalDate nextExpiration, Integer daysUntilNextExpiration) {

    /** Builds the summary, deriving {@code daysUntilNextExpiration} (negative when already expired) from {@code today}. */
    public static ComplianceSummary of(VendorCompliance status, int missing, int expired, int expiring,
            int reviewRequired, int ok, LocalDate nextExpiration, LocalDate today) {
        Integer days = nextExpiration == null ? null : (int) ChronoUnit.DAYS.between(today, nextExpiration);
        return new ComplianceSummary(status, missing, expired, expiring, reviewRequired, ok, nextExpiration, days);
    }
}
