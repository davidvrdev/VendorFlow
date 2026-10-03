package com.vendorflow.document.api;

import com.vendorflow.document.domain.ReviewStatus;
import com.vendorflow.shared.validation.PlainText;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of POST /documents/{id}/review. A rejection needs a note (checked in the service: 400 on {@code note}). */
public record ReviewRequest(
        @NotNull Decision decision,
        @Size(max = 1000) @PlainText(allowLineBreaks = true) String note) {

    public enum Decision {
        APPROVED(ReviewStatus.APPROVED), REJECTED(ReviewStatus.REJECTED);

        private final ReviewStatus status;

        Decision(ReviewStatus status) {
            this.status = status;
        }

        public ReviewStatus status() {
            return status;
        }
    }

    public ReviewRequest {
        note = note == null || note.isBlank() ? null : note.strip();
    }
}
