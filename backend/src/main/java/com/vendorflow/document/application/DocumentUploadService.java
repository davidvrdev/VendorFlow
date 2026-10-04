package com.vendorflow.document.application;

import com.vendorflow.audit.AuditService;
import com.vendorflow.document.api.DocumentSummary;
import com.vendorflow.document.domain.Document;
import com.vendorflow.document.domain.DocumentState;
import com.vendorflow.document.domain.DocumentType;
import com.vendorflow.document.domain.ReviewStatus;
import com.vendorflow.document.infrastructure.DocumentRepository;
import com.vendorflow.document.infrastructure.DocumentTypeRepository;
import com.vendorflow.document.infrastructure.storage.FileScanner;
import com.vendorflow.document.infrastructure.storage.ObjectStorage;
import com.vendorflow.document.infrastructure.storage.ScannerUnavailableException;
import com.vendorflow.document.infrastructure.storage.StorageKeys;
import com.vendorflow.organization.application.AuthorizationService;
import com.vendorflow.shared.tenant.TenantContext;
import com.vendorflow.organization.domain.Permission;
import com.vendorflow.shared.error.ApiException;
import com.vendorflow.shared.error.FieldViolation;
import com.vendorflow.shared.error.RequestValidationException;
import com.vendorflow.shared.ratelimit.RateLimiter;
import com.vendorflow.vendor.application.VendorService;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * The upload pipeline. Cheap checks first, nothing is stored until every validation passed:
 * <ol>
 * <li>authorization (CONTENT_WRITE), vendor belongs to the caller organization (404)</li>
 * <li>file present and non-empty (400) -> size (413) -> extension (415) -> magic bytes (415)</li>
 * <li>document type is an active type of the organization (400) -> dates (400)</li>
 * <li>malware-scan hook (422)</li>
 * <li>object written to storage (streamed; SHA-256 and the real byte count computed on the way)</li>
 * <li>ONE database transaction: lock the vendor row, supersede the CURRENT document, insert, audit.</li>
 * </ol>
 * The object is stored BEFORE the transaction (a database transaction must not span slow file I/O); if the
 * transaction fails the object is deleted again (best effort), so a failure leaves at most an orphan file, never a
 * row pointing at nothing.
 */
@Service
public class DocumentUploadService {

    private static final Logger log = LoggerFactory.getLogger(DocumentUploadService.class);
    static final String USER_RATE_RULE = "document-upload-user";
    private static final String CURRENT_UNIQUE_INDEX = "document_current_uq";

    private final AuthorizationService authorization;
    private final VendorService vendors;
    private final DocumentTypeRepository types;
    private final DocumentRepository documents;
    private final DocumentReadService reads;
    private final ObjectStorage storage;
    private final FileScanner scanner;
    private final AuditService audit;
    private final DocumentProperties properties;
    private final Clock clock;
    private final RateLimiter rateLimiter;
    private final TransactionTemplate tx;

    public DocumentUploadService(AuthorizationService authorization, VendorService vendors,
            DocumentTypeRepository types, DocumentRepository documents, DocumentReadService reads,
            ObjectStorage storage, FileScanner scanner, AuditService audit, DocumentProperties properties,
            Clock clock, RateLimiter rateLimiter, PlatformTransactionManager transactionManager) {
        this.authorization = authorization;
        this.vendors = vendors;
        this.types = types;
        this.documents = documents;
        this.reads = reads;
        this.storage = storage;
        this.scanner = scanner;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
        this.rateLimiter = rateLimiter;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Not @Transactional on purpose: the file is stored outside the database transaction (see class comment). */
    public DocumentSummary upload(UUID vendorId, MultipartFile file, String documentTypeIdText, String issueDateText,
            String expirationDateText) {
        TenantContext.Tenant tenant = authorization.require(Permission.CONTENT_WRITE);
        UUID orgId = tenant.organizationId();
        // Per-USER budget (the per-IP rule in RateLimitFilter cannot see the user): shared NATs do not starve each
        // other and one user rotating IPs is still limited. Counted before any other work.
        RateLimiter.Decision decision = rateLimiter.tryAcquire(USER_RATE_RULE, tenant.userId().toString());
        if (!decision.allowed()) {
            log.warn("Rate limit exceeded: rule={}", USER_RATE_RULE);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "Too many requests",
                    "Too many requests. Please try again later.", decision.retryAfterSeconds());
        }
        return process(new Uploader(orgId, tenant.userId(), null, null, null, null), vendorId, file, documentTypeIdText,
                issueDateText, expirationDateText);
    }

    /**
     * Upload through a vendor-portal link (ADR-0011). The caller (PortalService) has already verified the link, the
     * vendor and the subscription; there is no user and no tenant context, so the organization comes from the link.
     * Everything else is the staff pipeline. Not @Transactional, like {@link #upload}.
     */
    public DocumentSummary uploadFromPortal(PortalUpload portal, MultipartFile file, String documentTypeIdText,
            String issueDateText, String expirationDateText) {
        return process(new Uploader(portal.organizationId(), null, portal.linkId(), portal.allowedTypeIds(), portal.today(),
                portal.inTransaction()), portal.vendorId(), file, documentTypeIdText, issueDateText,
                expirationDateText);
    }

    /** What the portal tells the pipeline: tenant, vendor, link, allowed types, and a callback run INSIDE the insert transaction. */
    public record PortalUpload(UUID organizationId, UUID vendorId, UUID linkId, Set<UUID> allowedTypeIds, LocalDate today,
            Consumer<DocumentSummary> inTransaction) {
    }

    /** Who is uploading: a user (staff) or a portal link; allowedTypeIds and hook are only set for the portal. */
    private record Uploader(UUID organizationId, UUID userId, UUID linkId, Set<UUID> allowedTypeIds, LocalDate today,
            Consumer<DocumentSummary> hook) {
    }

    private DocumentSummary process(Uploader uploader, UUID vendorId, MultipartFile file, String documentTypeIdText,
            String issueDateText, String expirationDateText) {
        UUID orgId = uploader.organizationId();
        vendors.requireExists(orgId, vendorId);

        // 1. present and non-empty
        if (file == null || file.isEmpty()) {
            throw new RequestValidationException("file", "a non-empty file is required");
        }
        // 2. size (the declared size is only a fast pre-check; the streamed byte count below is the authority)
        long maxBytes = properties.maxSizeBytes();
        if (file.getSize() > maxBytes) {
            throw tooLarge(maxBytes);
        }
        // 3. extension of the sanitized name
        FilenameSanitizer.Result name = FilenameSanitizer.sanitize(file.getOriginalFilename());
        FileKind kind = FileKind.forExtension(name.extension()).orElseThrow(() -> unsupported(
                "Only PDF, PNG and JPEG files are accepted."));
        // 4. magic bytes must match the extension family (client Content-Type is never consulted)
        byte[] header = header(file);
        if (!kind.matches(header, header.length)) {
            throw unsupported("The file content does not match its extension.");
        }
        // 5. document type
        DocumentType type = activeType(orgId, documentTypeIdText, uploader.allowedTypeIds());
        // 6. dates
        List<FieldViolation> errors = new ArrayList<>();
        LocalDate issue = DocumentDates.parse("issueDate", issueDateText, errors);
        LocalDate expiration = DocumentDates.parse("expirationDate", expirationDateText, errors);
        DocumentDates.validate(issue, expiration, type.isHasExpiration(), errors);
        if (!errors.isEmpty()) {
            throw new RequestValidationException(errors);
        }
        // 7. storage quota, fast pre-check with the declared size (authoritative re-check in persist())
        if (documents.totalSizeBytes(orgId) + file.getSize() > properties.orgQuotaBytes()) {
            throw quotaExceeded();
        }
        scan(file, kind);

        UUID documentId = UUID.randomUUID();
        String key = StorageKeys.documentKey(orgId, documentId);
        StoredFile stored = store(key, file, maxBytes);
        try {
            return tx.execute(status -> persist(uploader, vendorId, type, documentId, key, name.filename(), kind,
                    stored, issue, expiration));
        } catch (RuntimeException e) {
            discard(key);
            if (e instanceof DataIntegrityViolationException dive && indexViolated(dive, CURRENT_UNIQUE_INDEX)) {
                throw new ApiException(HttpStatus.CONFLICT, "upload-conflict", "Upload conflict",
                        "Another upload for this document type is in progress. Please try again.");
            }
            throw e;
        }
    }

    // ---- steps ----

    private static byte[] header(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(FileKind.HEADER_BYTES);
        } catch (IOException e) {
            throw new org.springframework.web.multipart.MultipartException("Cannot read upload", e);
        }
    }

    private DocumentType activeType(UUID orgId, String text, Set<UUID> allowedTypeIds) {
        UUID id = null;
        if (text != null && !text.isBlank()) {
            try {
                id = UUID.fromString(text.strip());
            } catch (IllegalArgumentException ignored) {
                // falls through to the same error as an unknown id: no oracle
            }
        }
        Optional<DocumentType> type = id == null ? Optional.empty() : types.findByIdAndOrganizationId(id, orgId);
        // Same answer for missing, malformed, foreign and inactive.
        return type.filter(DocumentType::isActive)
                .filter(t -> allowedTypeIds == null || allowedTypeIds.contains(t.getId())).orElseThrow(
                () -> new RequestValidationException("documentTypeId", "must be an active document type"));
    }

    private void scan(MultipartFile file, FileKind kind) {
        FileScanner.Result result;
        try (InputStream in = file.getInputStream()) {
            result = scanner.scan(in, kind.mimeType());
        } catch (IOException e) {
            throw new org.springframework.web.multipart.MultipartException("Cannot read upload", e);
        } catch (ScannerUnavailableException e) {
            // Fail closed: no verdict means no upload. The cause stays in the log, not in the response.
            log.error("File scanner unavailable, upload refused: {}", e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "file-scanning-unavailable",
                    "File scanning unavailable", "Files cannot be accepted right now. Please try again later.");
        }
        if (!result.clean()) {
            log.warn("Upload rejected by file scanner: reason={}", result.reason());
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "file-rejected", "File rejected",
                    "The file was rejected by the security scan.");
        }
    }

    private record StoredFile(long sizeBytes, String sha256) {
    }

    /** Streams to storage while hashing and counting. Memory use is constant regardless of file size. */
    private StoredFile store(String key, MultipartFile file, long maxBytes) {
        MessageDigest digest = newSha256();
        try (InputStream raw = file.getInputStream()) {
            LimitedInputStream limited = new LimitedInputStream(raw, maxBytes);
            storage.put(key, new DigestInputStream(limited, digest), file.getSize());
            if (limited.count() == 0) {
                discard(key);
                throw new RequestValidationException("file", "a non-empty file is required");
            }
            return new StoredFile(limited.count(), HexFormat.of().formatHex(digest.digest()));
        } catch (IOException e) {
            throw new org.springframework.web.multipart.MultipartException("Cannot read upload", e);
        }
    }

    private DocumentSummary persist(Uploader uploader, UUID vendorId, DocumentType type, UUID documentId,
            String key, String filename, FileKind kind, StoredFile stored, LocalDate issue, LocalDate expiration) {
        UUID orgId = uploader.organizationId();
        Instant now = clock.instant();
        vendors.requireExistsAndLock(orgId, vendorId);
        // Authoritative quota check with the streamed byte count; the transaction rolls back and upload() deletes
        // the stored object. Concurrent uploads to DIFFERENT vendors of one org are not serialized (the lock is per
        // vendor), so the quota can be overshot by at most (concurrent uploads x max-size); accepted, see SECURITY.md.
        if (documents.totalSizeBytes(orgId) + stored.sizeBytes() > properties.orgQuotaBytes()) {
            throw quotaExceeded();
        }

        Optional<Document> current = documents.findCurrentForUpdate(orgId, vendorId, type.getId(),
                DocumentState.CURRENT);
        // M1 (ADR-0011): a vendor must never displace an APPROVED, unexpired document. Such a portal upload is stored
        // as a CANDIDATE: the approved document stays CURRENT and keeps counting; staff approval swaps them.
        boolean candidate = uploader.linkId() != null && current.filter(c -> c.getReviewStatus() == ReviewStatus.APPROVED
                && !(type.isHasExpiration() && c.getExpirationDate() != null
                        && c.getExpirationDate().isBefore(uploader.today()))).isPresent();
        // A pending candidate is always replaced by a newer upload (staff or portal): at most one per requirement.
        Optional<Document> oldCandidate = documents.findCurrentForUpdate(orgId, vendorId, type.getId(),
                DocumentState.CANDIDATE);
        Optional<Document> previous = candidate ? Optional.empty() : current;
        previous.ifPresent(old -> old.supersede(documentId, now));
        oldCandidate.ifPresent(old -> old.supersede(documentId, now));
        // Flush the UPDATEs before the INSERT: Hibernate would insert first, and the partial unique indexes allow only
        // one CURRENT (and one CANDIDATE) row per (vendor, type). (superseded_by FK is deferred.)
        documents.flush();

        Document document = documents.saveAndFlush(new Document(documentId, orgId, vendorId, type.getId(), issue,
                expiration, key, filename, kind.mimeType(), stored.sizeBytes(), stored.sha256(), uploader.userId(),
                uploader.linkId(), candidate, now));

        Map<String, Object> uploaded = metadata(vendorId, type, filename);
        uploaded.put("sizeBytes", stored.sizeBytes());
        uploaded.put("mimeType", kind.mimeType());
        uploaded.put("sha256", stored.sha256());
        if (uploader.linkId() != null) {
            uploaded.put("source", "PORTAL");
            uploaded.put("linkId", uploader.linkId().toString());
        }
        if (candidate) {
            uploaded.put("candidate", true);
        }
        // Explicit organization/actor: a portal upload has no TenantContext (and no user).
        audit.record(orgId, uploader.userId(), "document.uploaded", "document", documentId, uploaded);
        for (Document old : Stream.concat(previous.stream(), oldCandidate.stream()).toList()) {
            Map<String, Object> superseded = metadata(vendorId, type, old.getOriginalFilename());
            superseded.put("supersededBy", documentId.toString());
            if (uploader.linkId() != null) {
                superseded.put("source", "PORTAL");
            }
            audit.record(orgId, uploader.userId(), "document.superseded", "document", old.getId(), superseded);
        }
        log.info("Document uploaded: id={} org={} vendor={} bytes={} mime={} sha256={}", documentId, orgId, vendorId,
                stored.sizeBytes(), kind.mimeType(), stored.sha256());
        DocumentSummary summary = reads.summarize(document, type);
        if (uploader.hook() != null) {
            // Inside the transaction: claiming the link use and queueing the staff e-mail commit or roll back with the row.
            uploader.hook().accept(summary);
        }
        return summary;
    }

    /** Common audit metadata of document events: vendorId drives the vendor history query. */
    static Map<String, Object> metadata(UUID vendorId, DocumentType type, String filename) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vendorId", vendorId.toString());
        m.put("documentType", type.getCode());
        m.put("documentTypeName", type.getName());
        m.put("filename", filename);
        return m;
    }

    /** Best effort: a failed cleanup only leaves an orphan file; it must never mask the original error. */
    private void discard(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("Could not delete orphaned object after failed upload: key={}", key);
        }
    }

    private static boolean indexViolated(DataIntegrityViolationException e, String index) {
        String message = e.getMostSpecificCause().getMessage();
        return message != null && message.contains(index);
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static ApiException tooLarge(long maxBytes) {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "file-too-large", "File too large",
                "The file exceeds the maximum size of " + (maxBytes / (1024 * 1024)) + " MB.");
    }

    private static ApiException quotaExceeded() {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "storage-quota-exceeded", "Storage quota exceeded",
                "Your organization has reached its document storage quota.");
    }

    private static ApiException unsupported(String detail) {
        return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported-file-type", "Unsupported file type",
                detail);
    }

    /** Counts bytes and fails with 413 as soon as more than {@code max} are read (never trusts the declared size). */
    private static final class LimitedInputStream extends FilterInputStream {

        private final long max;
        private long count;

        LimitedInputStream(InputStream in, long max) {
            super(in);
            this.max = max;
        }

        long count() {
            return count;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                add(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            int n = super.read(buffer, off, len);
            if (n > 0) {
                add(n);
            }
            return n;
        }

        @Override
        public long skip(long n) {
            return 0; // never skip: every byte must be counted and hashed
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        private void add(long n) {
            count += n;
            if (count > max) {
                throw tooLarge(max);
            }
        }
    }
}
