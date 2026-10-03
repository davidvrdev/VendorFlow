package com.vendorflow.dashboard.api;

import java.time.LocalDate;

/** API shape "DashboardSummary" (docs/API.md Phase 5). Counts cover ACTIVE vendors and active document types only. */
public record DashboardSummary(LocalDate today, int expiringWindowDays, Vendors vendors, Documents documents) {

    public record Vendors(long active, long compliant, long attention, long nonCompliant, long noRequirements) {
    }

    /** Requirement counts (one per vendor requirement), not file counts. */
    public record Documents(long missing, long expired, long expiring, long reviewRequired) {
    }
}
