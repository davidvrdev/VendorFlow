package com.vendorflow.document.api;

import com.vendorflow.document.domain.DocumentType;
import java.util.UUID;

/** API shape "DocumentType" (docs/API.md); also what other features get from DocumentTypeService (never the entity). */
public record DocumentTypeView(UUID id, String code, String name, boolean hasExpiration, boolean requiredByDefault,
        int sortOrder) {

    public static DocumentTypeView from(DocumentType t) {
        return new DocumentTypeView(t.getId(), t.getCode(), t.getName(), t.isHasExpiration(),
                t.isRequiredByDefault(), t.getSortOrder());
    }
}
