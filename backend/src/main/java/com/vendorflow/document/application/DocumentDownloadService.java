package com.vendorflow.document.application;

import com.vendorflow.audit.AuditService;
import com.vendorflow.document.domain.Document;
import com.vendorflow.document.domain.DocumentType;
import com.vendorflow.document.infrastructure.DocumentRepository;
import com.vendorflow.document.infrastructure.DocumentTypeRepository;
import com.vendorflow.document.infrastructure.storage.ObjectNotFoundException;
import com.vendorflow.document.infrastructure.storage.ObjectStorage;
import com.vendorflow.organization.application.AuthorizationService;
import com.vendorflow.organization.application.TenantContext;
import com.vendorflow.organization.domain.Permission;
import com.vendorflow.shared.error.NotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Authorized download: authorize, open the object, audit, then hand the stream to the controller. */
@Service
public class DocumentDownloadService {

    private static final Logger log = LoggerFactory.getLogger(DocumentDownloadService.class);

    /** The caller MUST close {@code stream}. */
    public record Download(String filename, String mimeType, long sizeBytes, InputStream stream) {
    }

    private final AuthorizationService authorization;
    private final DocumentRepository documents;
    private final DocumentTypeRepository types;
    private final ObjectStorage storage;
    private final AuditService audit;

    public DocumentDownloadService(AuthorizationService authorization, DocumentRepository documents,
            DocumentTypeRepository types, ObjectStorage storage, AuditService audit) {
        this.authorization = authorization;
        this.documents = documents;
        this.types = types;
        this.storage = storage;
        this.audit = audit;
    }

    /**
     * Order: authorization -> the object must exist (else 404, no audit row for a download that did not happen) ->
     * audit {@code document.downloaded} (committed with this transaction, i.e. BEFORE any byte is sent) -> stream.
     * Any state of the document (CURRENT, SUPERSEDED, ARCHIVED) can be downloaded: history stays reachable.
     */
    @Transactional
    public Download open(UUID id) {
        TenantContext.Tenant tenant = authorization.require(Permission.DOCUMENTS_DOWNLOAD);
        Document document = documents.findByIdAndOrganizationId(id, tenant.organizationId())
                .orElseThrow(() -> new NotFoundException("Document not found."));
        DocumentType type = types.findByIdAndOrganizationId(document.getDocumentTypeId(), document.getOrganizationId())
                .orElseThrow(() -> new NotFoundException("Document not found."));

        InputStream stream;
        try {
            stream = storage.get(document.getStorageKey());
        } catch (ObjectNotFoundException e) {
            // Metadata without a file: data loss or misconfigured storage. Operators must hear about it; the client
            // just gets a 404 problem (no key, no path, no stack trace).
            log.error("Stored object is missing: documentId={} org={} key={}", id, tenant.organizationId(),
                    document.getStorageKey());
            throw new NotFoundException("The file of this document is not available.");
        }
        try {
            Map<String, Object> metadata = DocumentUploadService.metadata(document.getVendorId(), type,
                    document.getOriginalFilename());
            audit.record("document.downloaded", DocumentService.ENTITY_TYPE, id, metadata);
        } catch (RuntimeException e) {
            close(stream);
            throw e;
        }
        return new Download(document.getOriginalFilename(), document.getMimeType(), document.getSizeBytes(), stream);
    }

    private static void close(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }
}
