package com.vendorflow.document.application;

import com.vendorflow.document.api.DocumentSummary;
import com.vendorflow.document.domain.Document;
import com.vendorflow.document.domain.DocumentState;
import com.vendorflow.document.domain.DocumentType;
import com.vendorflow.document.infrastructure.DocumentRepository;
import com.vendorflow.document.infrastructure.DocumentRow;
import com.vendorflow.identity.application.UserAccountService;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read model of documents. NO authorization here: callers (DocumentService, VendorService) authorize first and pass
 * the organization id of the tenant. Kept free of any dependency on the vendor feature so VendorService can use it
 * without a bean cycle.
 */
@Service
public class DocumentReadService {

    /** Upper bound of one listing (docs/API.md). A vendor has one CURRENT per type plus history; this is generous. */
    public static final int MAX_ROWS = 500;

    private final DocumentRepository documents;
    private final UserAccountService users;

    public DocumentReadService(DocumentRepository documents, UserAccountService users) {
        this.documents = documents;
        this.users = users;
    }

    /** CURRENT documents, or every state when {@code includeHistory}; by type sortOrder then newest first. */
    @Transactional(readOnly = true)
    public List<DocumentSummary> findByVendor(UUID organizationId, UUID vendorId, boolean includeHistory) {
        Set<DocumentState> states = includeHistory ? Set.of(DocumentState.values()) : Set.of(DocumentState.CURRENT);
        return summarize(documents.findRows(organizationId, vendorId, states, PageRequest.of(0, MAX_ROWS)));
    }

    @Transactional(readOnly = true)
    public Optional<DocumentSummary> findById(UUID organizationId, UUID documentId) {
        return documents.findRow(documentId, organizationId).map(row -> summarize(List.of(row)).get(0));
    }

    /** Summary of a document the caller already loaded (e.g. after changing it). */
    @Transactional(readOnly = true)
    public DocumentSummary summarize(Document document, DocumentType type) {
        return summarize(List.of(new DocumentRow(document, type))).get(0);
    }

    private List<DocumentSummary> summarize(List<DocumentRow> rows) {
        Set<UUID> userIds = new HashSet<>();
        for (DocumentRow row : rows) {
            if (row.document().getUploadedByUserId() != null) {
                userIds.add(row.document().getUploadedByUserId());
            }
            if (row.document().getReviewedByUserId() != null) {
                userIds.add(row.document().getReviewedByUserId());
            }
        }
        Map<UUID, String> names = users.fullNames(userIds); // one query for the whole list: no N+1
        return rows.stream().map(row -> toSummary(row, names)).toList();
    }

    private static DocumentSummary toSummary(DocumentRow row, Map<UUID, String> names) {
        Document d = row.document();
        DocumentType t = row.type();
        return new DocumentSummary(d.getId(), d.getVendorId(),
                new DocumentSummary.TypeRef(t.getId(), t.getCode(), t.getName(), t.isHasExpiration()), d.getState(),
                d.getReviewStatus(), d.getIssueDate(), d.getExpirationDate(), d.getOriginalFilename(),
                d.getMimeType(), d.getSizeBytes(), userRef(names, d.getUploadedByUserId()), d.getCreatedAt(),
                userRef(names, d.getReviewedByUserId()), d.getReviewedAt(), d.getReviewNote());
    }

    private static DocumentSummary.UserRef userRef(Map<UUID, String> names, UUID userId) {
        String name = userId == null ? null : names.get(userId);
        return name == null ? null : new DocumentSummary.UserRef(name);
    }
}
