package com.vendorflow.document.api;

import com.vendorflow.document.domain.DocumentType;
import java.util.UUID;

/** API shape "DocumentTypeAdmin" = DocumentType + active (management screens, REQUIREMENTS_MANAGE). */
public record DocumentTypeAdminView(UUID id, String code, String name, boolean hasExpiration,
        boolean requiredByDefault, int sortOrder, boolean active) {

    public static DocumentTypeAdminView from(DocumentType t) {
        return new DocumentTypeAdminView(t.getId(), t.getCode(), t.getName(), t.isHasExpiration(),
                t.isRequiredByDefault(), t.getSortOrder(), t.isActive());
    }
}
