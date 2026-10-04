package com.vendorflow.document.application;

import com.vendorflow.audit.AuditService;
import com.vendorflow.document.api.DocumentSummary;
import com.vendorflow.document.api.ReviewRequest;
import com.vendorflow.document.domain.Document;
import com.vendorflow.document.domain.DocumentState;
import com.vendorflow.document.domain.DocumentType;
import com.vendorflow.document.domain.ReviewStatus;
import com.vendorflow.document.infrastructure.DocumentRepository;
import com.vendorflow.document.infrastructure.DocumentTypeRepository;
import com.vendorflow.organization.application.AuthorizationService;
import com.vendorflow.shared.tenant.TenantContext;
import com.vendorflow.organization.domain.Permission;
import com.vendorflow.shared.error.ApiException;
import com.vendorflow.shared.error.FieldViolation;
import com.vendorflow.shared.error.NotFoundException;
import com.vendorflow.shared.error.RequestValidationException;
import com.vendorflow.vendor.application.VendorService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use cases on existing documents. Every document is loaded by (id, organizationId) from the TenantContext: a
 * document of another organization is a 404, exactly like a nonexistent one. State changes lock the document row so
 * they serialize with a concurrent supersede/archive of the same document.
 */
@Service
public class DocumentService {

    public static final String ENTITY_TYPE = "document";

    private final AuthorizationService authorization;
    private final VendorService vendors;
    private final DocumentRepository documents;
    private final DocumentTypeRepository types;
    private final DocumentReadService reads;
    private final AuditService audit;
    private final Clock clock;

    public DocumentService(AuthorizationService authorization, VendorService vendors, DocumentRepository documents,
            DocumentTypeRepository types, DocumentReadService reads, AuditService audit, Clock clock) {
        this.authorization = authorization;
        this.vendors = vendors;
        this.documents = documents;
        this.types = types;
        this.reads = reads;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<DocumentSummary> listForVendor(UUID vendorId, boolean includeHistory) {
        TenantContext.Tenant tenant = authorization.require(Permission.DATA_VIEW);
        vendors.requireExists(tenant.organizationId(), vendorId);
        List<DocumentSummary> result = reads.findByVendor(tenant.organizationId(), vendorId, includeHistory);
        if (includeHistory) {
            return result;
        }
        // The default listing is what matters now: CURRENT plus any CANDIDATE waiting for review.
        List<DocumentSummary> all = new ArrayList<>(result);
        all.addAll(reads.findCandidates(tenant.organizationId(), vendorId));
        return all;
    }

    @Transactional(readOnly = true)
    public DocumentSummary get(UUID id) {
        TenantContext.Tenant tenant = authorization.require(Permission.DATA_VIEW);
        return reads.findById(tenant.organizationId(), id).orElseThrow(DocumentService::notFound);
    }

    /**
     * Edits the dates. {@code body} is the raw JSON object: an ABSENT key means "unchanged", an explicit null
     * clears (issueDate; expirationDate only if the type has no expiration).
     */
    @Transactional
    public DocumentSummary changeDates(UUID id, Map<String, Object> body) {
        TenantContext.Tenant tenant = authorization.require(Permission.CONTENT_WRITE);
        Document document = lock(tenant, id);
        requireCurrent(document);
        DocumentType type = typeOf(document);

        List<FieldViolation> errors = new ArrayList<>();
        LocalDate issue = document.getIssueDate();
        LocalDate expiration = document.getExpirationDate();
        if (body.containsKey("issueDate")) {
            issue = dateValue("issueDate", body.get("issueDate"), errors);
        }
        if (body.containsKey("expirationDate")) {
            expiration = dateValue("expirationDate", body.get("expirationDate"), errors);
        }
        DocumentDates.validate(issue, expiration, type.isHasExpiration(), errors);
        if (!errors.isEmpty()) {
            throw new RequestValidationException(errors);
        }

        Map<String, Object> changes = new LinkedHashMap<>();
        diff(changes, "issueDate", document.getIssueDate(), issue);
        diff(changes, "expirationDate", document.getExpirationDate(), expiration);
        if (!changes.isEmpty()) {
            document.changeDates(issue, expiration, clock.instant());
            Map<String, Object> metadata = DocumentUploadService.metadata(document.getVendorId(), type,
                    document.getOriginalFilename());
            metadata.put("changes", changes);
            audit.record("document.dates_changed", ENTITY_TYPE, id, metadata);
        }
        return reads.summarize(document, type);
    }

    @Transactional
    public DocumentSummary review(UUID id, ReviewRequest request) {
        TenantContext.Tenant tenant = authorization.require(Permission.DOCUMENTS_REVIEW);
        ReviewStatus decision = request.decision().status();
        Document peek = documents.findByIdAndOrganizationId(id, tenant.organizationId())
                .orElseThrow(DocumentService::notFound);
        if (peek.getState() == DocumentState.CANDIDATE) {
            return reviewCandidate(tenant, peek, request, decision);
        }
        Document document = lock(tenant, id);
        requireCurrent(document);
        if (decision == ReviewStatus.REJECTED && (request.note() == null || request.note().isBlank())) {
            throw new RequestValidationException("note", "is required when rejecting a document");
        }
        DocumentType type = typeOf(document);

        Map<String, Object> change = new LinkedHashMap<>();
        change.put("before", document.getReviewStatus().name());
        change.put("after", decision.name());
        document.review(decision, request.note(), tenant.userId(), clock.instant());

        Map<String, Object> metadata = DocumentUploadService.metadata(document.getVendorId(), type,
                document.getOriginalFilename());
        metadata.put("decision", decision.name());
        if (request.note() != null) {
            metadata.put("note", request.note());
        }
        metadata.put("changes", Map.of("reviewStatus", change));
        audit.record("document.reviewed", ENTITY_TYPE, id, metadata);
        return reads.summarize(document, type);
    }

    /**
     * Review of a portal CANDIDATE (M1). The vendor row is locked FIRST (the same order as the upload pipeline, so the
     * two cannot deadlock). Approve: the CURRENT document is superseded and the candidate promoted in one transaction.
     * Reject: the CURRENT document stays untouched and the rejected candidate is archived (kept for history).
     */
    private DocumentSummary reviewCandidate(TenantContext.Tenant tenant, Document peek, ReviewRequest request,
            ReviewStatus decision) {
        UUID orgId = tenant.organizationId();
        vendors.requireExistsAndLock(orgId, peek.getVendorId());
        // Re-checked under the lock: the candidate may just have been replaced by a newer upload.
        Document candidate = documents.findCurrentForUpdate(orgId, peek.getVendorId(), peek.getDocumentTypeId(),
                DocumentState.CANDIDATE).filter(c -> c.getId().equals(peek.getId()))
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "document-not-current",
                        "Document is not current", "This document was already replaced or decided."));
        if (decision == ReviewStatus.REJECTED && (request.note() == null || request.note().isBlank())) {
            throw new RequestValidationException("note", "is required when rejecting a document");
        }
        DocumentType type = typeOf(candidate);
        Instant now = clock.instant();
        if (decision == ReviewStatus.APPROVED) {
            Optional<Document> old = documents.findCurrentForUpdate(orgId, candidate.getVendorId(),
                    candidate.getDocumentTypeId(), DocumentState.CURRENT);
            old.ifPresent(o -> o.supersede(candidate.getId(), now));
            // The old row leaves CURRENT before the candidate enters it (partial unique index document_current_uq).
            documents.flush();
            candidate.promote(now);
            old.ifPresent(o -> {
                Map<String, Object> superseded = DocumentUploadService.metadata(o.getVendorId(), type,
                        o.getOriginalFilename());
                superseded.put("supersededBy", candidate.getId().toString());
                superseded.put("source", candidate.getSource().name());
                audit.record("document.superseded", ENTITY_TYPE, o.getId(), superseded);
            });
        }
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("before", candidate.getReviewStatus().name());
        change.put("after", decision.name());
        candidate.review(decision, request.note(), tenant.userId(), now);
        if (decision == ReviewStatus.REJECTED) {
            candidate.archive(now);
        }
        Map<String, Object> metadata = DocumentUploadService.metadata(candidate.getVendorId(), type,
                candidate.getOriginalFilename());
        metadata.put("decision", decision.name());
        metadata.put("candidate", true);
        if (request.note() != null) {
            metadata.put("note", request.note());
        }
        metadata.put("changes", Map.of("reviewStatus", change));
        audit.record("document.reviewed", ENTITY_TYPE, candidate.getId(), metadata);
        documents.flush();
        return reads.summarize(candidate, type);
    }

    /** CURRENT, CANDIDATE or SUPERSEDED -> ARCHIVED. Already archived: no-op. Archiving the CURRENT one promotes nothing. */
    @Transactional
    public DocumentSummary archive(UUID id) {
        TenantContext.Tenant tenant = authorization.require(Permission.ARCHIVE_AND_IMPORT);
        Document document = lock(tenant, id);
        DocumentType type = typeOf(document);
        if (document.getState() != DocumentState.ARCHIVED) {
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("before", document.getState().name());
            change.put("after", DocumentState.ARCHIVED.name());
            document.archive(clock.instant());
            Map<String, Object> metadata = DocumentUploadService.metadata(document.getVendorId(), type,
                    document.getOriginalFilename());
            metadata.put("changes", Map.of("state", change));
            audit.record("document.archived", ENTITY_TYPE, id, metadata);
        }
        return reads.summarize(document, type);
    }

    // ---- helpers ----

    private Document lock(TenantContext.Tenant tenant, UUID id) {
        return documents.findForUpdate(id, tenant.organizationId()).orElseThrow(DocumentService::notFound);
    }

    private DocumentType typeOf(Document document) {
        return types.findByIdAndOrganizationId(document.getDocumentTypeId(), document.getOrganizationId())
                .orElseThrow(DocumentService::notFound);
    }

    private static void requireCurrent(Document document) {
        if (document.getState() != DocumentState.CURRENT) {
            throw new ApiException(HttpStatus.CONFLICT, "document-not-current", "Document is not current",
                    "Only the current document of a requirement can be changed.");
        }
    }

    /** null (and blank) clears; a string is parsed strictly; anything else is a type error. */
    private static LocalDate dateValue(String field, Object value, List<FieldViolation> errors) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            errors.add(new FieldViolation(field, "must be a date in the format yyyy-MM-dd"));
            return null;
        }
        return DocumentDates.parse(field, text, errors);
    }

    private static void diff(Map<String, Object> changes, String field, LocalDate before, LocalDate after) {
        if (before == null ? after != null : !before.equals(after)) {
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("before", before == null ? null : before.toString());
            change.put("after", after == null ? null : after.toString());
            changes.put(field, change);
        }
    }

    private static NotFoundException notFound() {
        return new NotFoundException("Document not found.");
    }
}
