package com.vendorflow.document.application;

import com.vendorflow.shared.error.FieldViolation;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/** Date rules shared by upload and PATCH: strict yyyy-MM-dd, 1990-01-01..2100-12-31, issue before expiration. */
final class DocumentDates {

    static final LocalDate MIN = LocalDate.of(1990, 1, 1);
    static final LocalDate MAX = LocalDate.of(2100, 12, 31);

    private DocumentDates() {
    }

    /** @return the date, or null with a violation added; blank text is "absent" (null, no violation) */
    static LocalDate parse(String field, String text, List<FieldViolation> errors) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(text.strip()); // ISO_LOCAL_DATE is strict: 2024-02-30 is rejected
            if (date.isBefore(MIN) || date.isAfter(MAX)) {
                errors.add(new FieldViolation(field, "must be between 1990-01-01 and 2100-12-31"));
                return null;
            }
            return date;
        } catch (DateTimeParseException e) {
            errors.add(new FieldViolation(field, "must be a date in the format yyyy-MM-dd"));
            return null;
        }
    }

    /** Cross-field rules on already parsed dates. */
    static void validate(LocalDate issue, LocalDate expiration, boolean expirationRequired,
            List<FieldViolation> errors) {
        boolean expirationInvalid = errors.stream().anyMatch(e -> e.field().equals("expirationDate"));
        if (expirationRequired && expiration == null && !expirationInvalid) {
            errors.add(new FieldViolation("expirationDate", "is required for this document type"));
        }
        if (issue != null && expiration != null && issue.isAfter(expiration)) {
            errors.add(new FieldViolation("issueDate", "must not be after the expiration date"));
        }
    }
}
