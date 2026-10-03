package com.vendorflow.compliance.domain;

import com.vendorflow.document.domain.ReviewStatus;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Optional;

/**
 * THE definition of the compliance rules (docs/API.md "Phase 4 contract details", ADR-0005). Pure Java: no Spring, no
 * clock (callers pass "today" computed in the organization time zone). The vendor list SQL
 * ({@code VendorSearchRepository}) re-implements the same rules; {@code compliance/vectors.csv} runs against both.
 */
public final class ComplianceCalculator {

    private ComplianceCalculator() {
    }

    /** The CURRENT document of a requirement, reduced to what the rules need. */
    public record DocState(ReviewStatus reviewStatus, LocalDate expirationDate) {
    }

    /** One evaluated requirement; {@code expirationDate} is what feeds "next expiration" (null when none counts). */
    public record Evaluated(RequirementStatus status, LocalDate expirationDate) {
    }

    /** First match wins. {@code currentDoc} empty = no CURRENT document. */
    public static RequirementStatus evaluate(boolean hasExpiration, Optional<DocState> currentDoc, LocalDate today,
            int windowDays) {
        if (currentDoc.isEmpty() || currentDoc.get().reviewStatus() == ReviewStatus.REJECTED) {
            return RequirementStatus.MISSING;
        }
        DocState doc = currentDoc.get();
        LocalDate expiration = doc.expirationDate();
        if (hasExpiration && expiration != null && expiration.isBefore(today)) {
            return RequirementStatus.EXPIRED; // before the review check: an approved or pending expired doc is EXPIRED
        }
        if (doc.reviewStatus() == ReviewStatus.PENDING || (hasExpiration && expiration == null)) {
            return RequirementStatus.REVIEW_REQUIRED;
        }
        if (hasExpiration && ChronoUnit.DAYS.between(today, expiration) <= windowDays) {
            return RequirementStatus.EXPIRING;
        }
        return RequirementStatus.OK;
    }

    /** {@code expirationDate - today} (negative when expired); null when the type has no expiration or no date counts. */
    public static Integer daysUntilExpiration(boolean hasExpiration, Optional<DocState> currentDoc, LocalDate today) {
        LocalDate date = countedExpiration(hasExpiration, currentDoc);
        return date == null ? null : (int) ChronoUnit.DAYS.between(today, date);
    }

    /** The date that feeds "nextExpiration": CURRENT, non-rejected document of an expiring type with a date. */
    public static LocalDate countedExpiration(boolean hasExpiration, Optional<DocState> currentDoc) {
        if (!hasExpiration || currentDoc.isEmpty() || currentDoc.get().reviewStatus() == ReviewStatus.REJECTED) {
            return null;
        }
        return currentDoc.get().expirationDate();
    }

    public static Evaluated evaluated(boolean hasExpiration, Optional<DocState> currentDoc, LocalDate today,
            int windowDays) {
        return new Evaluated(evaluate(hasExpiration, currentDoc, today, windowDays),
                countedExpiration(hasExpiration, currentDoc));
    }

    /** NON_COMPLIANT if any MISSING/EXPIRED; else ATTENTION if any REVIEW_REQUIRED/EXPIRING; else COMPLIANT. */
    public static ComplianceSummary summarize(Collection<Evaluated> requirements, LocalDate today) {
        int missing = 0;
        int expired = 0;
        int expiring = 0;
        int review = 0;
        int ok = 0;
        LocalDate next = null;
        for (Evaluated e : requirements) {
            switch (e.status()) {
                case MISSING -> missing++;
                case EXPIRED -> expired++;
                case EXPIRING -> expiring++;
                case REVIEW_REQUIRED -> review++;
                case OK -> ok++;
            }
            if (e.expirationDate() != null && (next == null || e.expirationDate().isBefore(next))) {
                next = e.expirationDate();
            }
        }
        VendorCompliance status = missing + expired > 0 ? VendorCompliance.NON_COMPLIANT
                : review + expiring > 0 ? VendorCompliance.ATTENTION : VendorCompliance.COMPLIANT;
        return ComplianceSummary.of(status, missing, expired, expiring, review, ok, next, today);
    }
}
