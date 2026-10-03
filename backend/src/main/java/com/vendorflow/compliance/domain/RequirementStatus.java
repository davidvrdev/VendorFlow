package com.vendorflow.compliance.domain;

/** Status of one vendor requirement (docs/API.md Phase 4). */
public enum RequirementStatus {
    MISSING, OK, EXPIRING, EXPIRED, REVIEW_REQUIRED
}
