package com.vendorflow.dashboard.api;

import com.vendorflow.compliance.domain.RequirementStatus;
import java.time.LocalDate;
import java.util.UUID;

/** API shape "AttentionItem": one requirement that is not OK, with the action that resolves it. */
public record AttentionItem(UUID vendorId, String vendorName, UUID documentTypeId, String documentTypeName,
        RequirementStatus status, UUID documentId, LocalDate expirationDate, Integer daysUntilExpiration,
        AttentionAction action) {

    public enum AttentionAction {
        UPLOAD, UPLOAD_RENEWAL, REVIEW;

        public static AttentionAction of(RequirementStatus status) {
            return switch (status) {
                case MISSING, EXPIRED -> UPLOAD;
                case EXPIRING -> UPLOAD_RENEWAL;
                case REVIEW_REQUIRED -> REVIEW;
                case OK -> throw new IllegalArgumentException("OK requirements never need attention");
            };
        }
    }
}
