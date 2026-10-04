package com.vendorflow.vendor.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** API shape "ImportPreview" (docs/API.md Phase 7). Row numbers are 1-based data rows, the header excluded. */
public record ImportPreview(UUID importId, Instant expiresAt, Summary summary, List<Row> rows) {

    public record Summary(int total, int create, int update, int unchanged, int error) {
    }

    /** {@code changes}: UPDATE only, the column names that will change. {@code errors}: ERROR only. */
    public record Row(int rowNumber, String companyName, ImportRowAction action, List<String> changes,
            List<RowError> errors) {
    }

    /** {@code field} is the CSV column name (or "row" / "file" for problems that belong to no single column). */
    public record RowError(String field, String message) {
    }
}
