package com.vendorflow.document.infrastructure;

import com.vendorflow.document.domain.Document;
import com.vendorflow.document.domain.DocumentType;

/** A document with its type, loaded in one query (no N+1 when listing). */
public record DocumentRow(Document document, DocumentType type) {
}
