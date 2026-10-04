package com.vendorflow.vendor.application;

import com.vendorflow.audit.AuditService;
import com.vendorflow.document.api.DocumentTypeView;
import com.vendorflow.document.application.DocumentTypeService;
import com.vendorflow.document.application.FilenameSanitizer;
import com.vendorflow.organization.application.AuthorizationService;
import com.vendorflow.shared.tenant.TenantContext;
import com.vendorflow.organization.domain.Permission;
import com.vendorflow.shared.error.ApiException;
import com.vendorflow.shared.error.NotFoundException;
import com.vendorflow.shared.error.RequestValidationException;
import com.vendorflow.shared.ratelimit.RateLimiter;
import com.vendorflow.vendor.api.ImportPreview;
import com.vendorflow.vendor.api.ImportResult;
import com.vendorflow.vendor.api.ImportRowAction;
import com.vendorflow.vendor.api.VendorRequest;
import com.vendorflow.vendor.domain.Vendor;
import com.vendorflow.vendor.domain.VendorImport;
import com.vendorflow.vendor.domain.VendorImportStatus;
import com.vendorflow.vendor.domain.VendorStatus;
import com.vendorflow.vendor.infrastructure.VendorImportRepository;
import com.vendorflow.vendor.infrastructure.VendorRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * CSV vendor import in two steps. PREVIEW parses and validates the file and stores the result (nothing touches
 * vendors); COMMIT re-checks the stored rows against the CURRENT data under a row lock and applies them in one
 * transaction, so the user only ever applies what they reviewed, once.
 *
 * <p>Validation reuses {@link VendorRequest} (the vendor API request record) through the Bean Validation
 * {@link Validator}: lengths, e-mail, phone and plain-text rules cannot drift from the API. Creating and updating go
 * through {@link VendorService#createVendor} / {@link VendorService#applyUpdate}, the same code the API uses
 * (default requirements, duplicate handling, audit).
 */
@Service
public class VendorImportService {

    private static final Logger log = LoggerFactory.getLogger(VendorImportService.class);
    static final String RATE_RULE = "vendor-import-preview-user";
    static final long MAX_FILE_BYTES = 1024 * 1024;
    static final Duration PREVIEW_TTL = Duration.ofHours(1);
    /** Expired previews stay this long (so "expired" can be answered with 410, not 404), then they are purged. */
    static final Duration PURGE_GRACE = Duration.ofHours(24);
    private static final String IMPORT_ENTITY = "vendor_import";

    private final AuthorizationService authorization;
    private final VendorService vendorService;
    private final VendorRepository vendors;
    private final VendorImportRepository imports;
    private final DocumentTypeService documentTypes;
    private final AuditService audit;
    private final RateLimiter rateLimiter;
    private final Validator validator;
    private final Clock clock;

    public VendorImportService(AuthorizationService authorization, VendorService vendorService,
            VendorRepository vendors, VendorImportRepository imports, DocumentTypeService documentTypes,
            AuditService audit, RateLimiter rateLimiter, Validator validator, Clock clock) {
        this.authorization = authorization;
        this.vendorService = vendorService;
        this.vendors = vendors;
        this.imports = imports;
        this.documentTypes = documentTypes;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.validator = validator;
        this.clock = clock;
    }

    // ---- preview ----

    @Transactional
    public ImportPreview preview(MultipartFile file) {
        TenantContext.Tenant tenant = authorization.require(Permission.ARCHIVE_AND_IMPORT);
        UUID orgId = tenant.organizationId();
        // Per-USER budget, counted before any file work (parsing is the expensive part).
        RateLimiter.Decision decision = rateLimiter.tryAcquire(RATE_RULE, tenant.userId().toString());
        if (!decision.allowed()) {
            log.warn("Rate limit exceeded: rule={}", RATE_RULE);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "Too many requests",
                    "Too many requests. Please try again later.", decision.retryAfterSeconds());
        }
        byte[] bytes = readChecked(file);
        VendorCsvParser.Parsed parsed = VendorCsvParser.parse(bytes);

        List<Stored> rows = new ArrayList<>();
        for (VendorCsvParser.Row row : parsed.rows()) {
            rows.add(validate(row));
        }
        // Unknown header columns are reported once, on the first row (docs/API.md Phase 7).
        Stored first = rows.get(0);
        for (String column : parsed.unknownColumns()) {
            first.errors().add(new ImportPreview.RowError(column, "Unknown column"));
        }
        if (parsed.moreUnknown() > 0) {
            first.errors().add(new ImportPreview.RowError("header", "and " + parsed.moreUnknown()
                    + " more unknown columns"));
        }
        markDuplicates(rows);

        Map<String, Vendor> existing = loadExisting(orgId, rows, false);
        List<Stored> resolved = rows.stream().map(r -> r.errors().isEmpty() ? resolve(r, existing) : r.asError())
                .toList();
        ImportPreview.Summary summary = summarize(resolved);

        Instant now = clock.instant();
        imports.deleteExpiredBefore(now.minus(PURGE_GRACE));
        VendorImport stored = imports.save(new VendorImport(orgId, tenant.userId(),
                resolved.stream().map(Stored::toMap).toList(), summaryMap(summary), now, now.plus(PREVIEW_TTL)));
        return new ImportPreview(stored.getId(), stored.getExpiresAt(), summary,
                resolved.stream().map(Stored::toPreviewRow).toList());
    }

    private byte[] readChecked(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new RequestValidationException("file", "a non-empty file is required");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw tooLarge();
        }
        if (!"csv".equals(FilenameSanitizer.sanitize(file.getOriginalFilename()).extension())) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported-file-type",
                    "Unsupported file type", "Only .csv files are accepted.");
        }
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length > MAX_FILE_BYTES) { // the declared size is only a hint; the real length decides
                throw tooLarge();
            }
            return bytes;
        } catch (IOException e) {
            throw new RequestValidationException("file", "the file could not be read");
        }
    }

    private static ApiException tooLarge() {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "file-too-large", "File too large",
                "The CSV file is larger than 1 MB. Split it into smaller files.");
    }

    /** Cell-level validation of one row with the vendor API rules. */
    private Stored validate(VendorCsvParser.Row row) {
        Map<String, String> c = row.cells();
        List<ImportPreview.RowError> errors = new ArrayList<>();
        // The constructor strips and normalizes exactly like the API does for JSON bodies.
        VendorRequest request = new VendorRequest(c.get(VendorCsv.COMPANY_NAME), c.get(VendorCsv.CONTACT_NAME),
                c.get(VendorCsv.EMAIL), c.get(VendorCsv.PHONE), c.get(VendorCsv.CATEGORY),
                normalizeLineBreaks(c.get(VendorCsv.NOTES)));
        for (ConstraintViolation<VendorRequest> v : validator.validate(request)) {
            errors.add(new ImportPreview.RowError(column(v.getPropertyPath().toString()), v.getMessage()));
        }
        for (String tooLong : row.tooLong()) {
            errors.add(new ImportPreview.RowError(tooLong, "Value too long"));
        }
        errors.sort((a, b) -> Integer.compare(VendorCsv.IMPORT_COLUMNS.indexOf(a.field()),
                VendorCsv.IMPORT_COLUMNS.indexOf(b.field())));

        String status = null;
        String statusText = c.get(VendorCsv.STATUS);
        if (statusText != null) {
            status = statusText.toUpperCase(Locale.ROOT);
            if (!status.equals("ACTIVE") && !status.equals("INACTIVE")) {
                errors.add(new ImportPreview.RowError(VendorCsv.STATUS, "must be ACTIVE or INACTIVE"));
                status = null;
            }
        }
        if (row.stray()) {
            errors.add(new ImportPreview.RowError("row", "Has data in a column without a header name"));
        }
        return new Stored(row.rowNumber(), request.companyName(), ImportRowAction.CREATE, new ArrayList<>(), errors,
                null, request.contactName(), request.email(), request.phone(), request.category(), request.notes(),
                status);
    }

    private static void markDuplicates(List<Stored> rows) {
        Map<String, List<Integer>> byName = new LinkedHashMap<>();
        for (Stored r : rows) {
            if (r.companyName() != null) {
                byName.computeIfAbsent(key(r.companyName()), k -> new ArrayList<>()).add(r.rowNumber());
            }
        }
        for (Stored r : rows) {
            List<Integer> same = r.companyName() == null ? List.of() : byName.get(key(r.companyName()));
            if (same.size() > 1) {
                r.errors().add(new ImportPreview.RowError(VendorCsv.COMPANY_NAME,
                        "Appears more than once in this file (rows "
                                + same.stream().map(String::valueOf).collect(Collectors.joining(", ")) + ")"));
            }
        }
    }

    // ---- shared by preview and commit ----

    /** One query for all vendors of the organization whose name appears in the file. */
    private Map<String, Vendor> loadExisting(UUID orgId, List<Stored> rows, boolean lock) {
        Set<String> names = rows.stream().map(Stored::companyName).filter(Objects::nonNull).map(
                VendorImportService::key).collect(Collectors.toSet());
        Map<String, Vendor> result = new HashMap<>();
        if (!names.isEmpty()) {
            (lock ? vendors.findByLowerNamesForUpdate(orgId, names) : vendors.findByLowerNames(orgId, names))
                    .forEach(v -> result.put(key(v.getCompanyName()), v));
        }
        return result;
    }

    /** The decision rule: matches by case-insensitive name; empty cells never erase existing data. */
    private static Stored resolve(Stored row, Map<String, Vendor> existing) {
        Vendor v = existing.get(key(row.companyName()));
        if (v == null) {
            return row.with(ImportRowAction.CREATE, List.of(), null);
        }
        List<String> changes = new ArrayList<>();
        if (!row.companyName().equals(v.getCompanyName())) {
            changes.add(VendorCsv.COMPANY_NAME);
        }
        if (differs(row.contactName(), v.getContactName())) {
            changes.add(VendorCsv.CONTACT_NAME);
        }
        if (differs(row.email(), v.getEmail())) {
            changes.add(VendorCsv.EMAIL);
        }
        if (differs(row.phone(), v.getPhone())) {
            changes.add(VendorCsv.PHONE);
        }
        if (differs(row.category(), v.getCategory())) {
            changes.add(VendorCsv.CATEGORY);
        }
        // Line-break style is not a change (Excel writes CRLF, browsers send LF).
        if (differs(row.notes(), normalizeLineBreaks(v.getNotes()))) {
            changes.add(VendorCsv.NOTES);
        }
        if (differs(row.status(), v.getStatus().name())) {
            changes.add(VendorCsv.STATUS);
        }
        return row.with(changes.isEmpty() ? ImportRowAction.UNCHANGED : ImportRowAction.UPDATE, changes, v.getId());
    }

    /** An empty cell (null) is never a difference. */
    private static boolean differs(String fromFile, String current) {
        return fromFile != null && !fromFile.equals(current);
    }

    private static ImportPreview.Summary summarize(List<Stored> rows) {
        int create = 0;
        int update = 0;
        int unchanged = 0;
        int error = 0;
        for (Stored r : rows) {
            switch (r.action()) {
                case CREATE -> create++;
                case UPDATE -> update++;
                case UNCHANGED -> unchanged++;
                case ERROR -> error++;
            }
        }
        return new ImportPreview.Summary(rows.size(), create, update, unchanged, error);
    }

    private static Map<String, Object> summaryMap(ImportPreview.Summary s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", s.total());
        m.put("create", s.create());
        m.put("update", s.update());
        m.put("unchanged", s.unchanged());
        m.put("error", s.error());
        return m;
    }

    // ---- commit ----

    @Transactional
    public ImportResult commit(UUID importId) {
        TenantContext.Tenant tenant = authorization.require(Permission.ARCHIVE_AND_IMPORT);
        UUID orgId = tenant.organizationId();
        // Foreign and nonexistent ids are the same 404. The row lock serializes concurrent commits of one import.
        VendorImport imp = imports.findForUpdate(importId, orgId)
                .orElseThrow(() -> new NotFoundException("Import not found."));
        if (imp.getStatus() == VendorImportStatus.COMMITTED) {
            throw new ApiException(HttpStatus.CONFLICT, "import-committed", "Import already committed",
                    "This import was already applied.");
        }
        if (!clock.instant().isBefore(imp.getExpiresAt())) {
            throw new ApiException(HttpStatus.GONE, "import-expired", "Import expired",
                    "This preview has expired. Upload the file again.");
        }
        List<Stored> rows = imp.getRows().stream().map(Stored::fromMap).toList();
        if (rows.stream().anyMatch(r -> r.action() == ImportRowAction.ERROR)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "import-has-errors", "Import has errors",
                    "The file has rows with errors. Fix them and upload the file again.");
        }

        // Stored rows are JSON in our own table, but they are re-validated like fresh input: a row that no longer
        // passes the vendor rules (tampering, a rule change since the preview) must never reach the vendor tables.
        for (Stored row : rows) {
            if (!stillValid(row)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "import-has-errors", "Import has errors",
                        "The file has rows with errors. Fix them and upload the file again.");
            }
        }

        // Lock the matched vendors (ordered by id, no deadlocks) BEFORE re-resolving: a concurrent edit either is
        // seen here (-> 409 if the outcome changed) or waits until this commit is done. No lost update.
        // Re-resolve every row against the CURRENT data: anything that no longer matches the preview aborts.
        Map<String, Vendor> existing = loadExisting(orgId, rows, true);
        List<Stored> current = rows.stream().map(r -> resolve(r, existing)).toList();
        for (int i = 0; i < rows.size(); i++) {
            if (!sameOutcome(rows.get(i), current.get(i))) {
                throw dataChanged();
            }
        }

        List<DocumentTypeView> defaults = documentTypes.findActiveRequiredByDefault(orgId);
        Map<String, Object> extras = Map.of("source", "csv_import", "importId", importId.toString());
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        try {
            for (Stored row : rows) {
                switch (row.action()) {
                    case CREATE -> {
                        vendorService.createVendor(tenant, row.toRequest(), row.statusOrNull(), defaults, extras);
                        created++;
                    }
                    case UPDATE -> {
                        Vendor vendor = existing.get(key(row.companyName()));
                        vendorService.applyUpdate(vendor, row.mergedInto(vendor), row.statusOrNull(), extras);
                        updated++;
                    }
                    default -> unchanged++;
                }
            }
        } catch (ApiException e) {
            // A vendor with that name appeared between the re-check and the insert (the unique index decided).
            if ("vendor-exists".equals(e.slug())) {
                throw dataChanged();
            }
            throw e;
        } catch (DataIntegrityViolationException e) {
            throw dataChanged();
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", rows.size());
        summary.put("created", created);
        summary.put("updated", updated);
        summary.put("unchanged", unchanged);
        audit.record("vendor.imported", IMPORT_ENTITY, importId, summary);
        imp.markCommitted(clock.instant());
        return new ImportResult(created, updated, unchanged);
    }

    private boolean stillValid(Stored row) {
        if (row.status() != null && !row.status().equals("ACTIVE") && !row.status().equals("INACTIVE")) {
            return false;
        }
        return validator.validate(row.toRequest()).isEmpty();
    }

    private static boolean sameOutcome(Stored previewed, Stored now) {
        return previewed.action() == now.action() && Objects.equals(previewed.vendorId(), now.vendorId())
                && previewed.changes().equals(now.changes());
    }

    private static ApiException dataChanged() {
        return new ApiException(HttpStatus.CONFLICT, "import-data-changed", "Data changed since preview",
                "Vendors changed after this preview was created. Upload the file again to preview it.");
    }

    // ---- cleanup ----

    /** Purges previews (they hold vendor contact data) a day after they expired, even if nobody previews again. */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT10M")
    @Transactional
    public void purgeExpiredPreviews() {
        int deleted = imports.deleteExpiredBefore(clock.instant().minus(PURGE_GRACE));
        if (deleted > 0) {
            log.info("Purged {} expired vendor import previews", deleted);
        }
    }

    // ---- helpers ----

    private static String key(String companyName) {
        return companyName.strip().toLowerCase(Locale.ROOT);
    }

    private static String column(String property) {
        return switch (property) {
            case "companyName" -> VendorCsv.COMPANY_NAME;
            case "contactName" -> VendorCsv.CONTACT_NAME;
            case "email" -> VendorCsv.EMAIL;
            case "phone" -> VendorCsv.PHONE;
            case "category" -> VendorCsv.CATEGORY;
            case "notes" -> VendorCsv.NOTES;
            default -> property;
        };
    }

    private static String normalizeLineBreaks(String value) {
        return value == null ? null : value.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * One parsed row as stored in vendor_import.rows (JSON): the preview fields plus the normalized cell values and
     * the matched vendor id that commit needs.
     */
    record Stored(int rowNumber, String companyName, ImportRowAction action, List<String> changes,
            List<ImportPreview.RowError> errors, UUID vendorId, String contactName, String email, String phone,
            String category, String notes, String status) {

        Stored with(ImportRowAction newAction, List<String> newChanges, UUID newVendorId) {
            return new Stored(rowNumber, companyName, newAction, List.copyOf(newChanges), errors, newVendorId,
                    contactName, email, phone, category, notes, status);
        }

        Stored asError() {
            // An ERROR row is never applied, so it keeps no more than each column could hold (never a raw huge value).
            return new Stored(rowNumber, cut(companyName, 200), ImportRowAction.ERROR, List.of(), List.copyOf(errors),
                    null, cut(contactName, 120), cut(email, 254), cut(phone, 40), cut(category, 60),
                    cut(notes, 5000), cut(status, 20));
        }

        private static String cut(String value, int max) {
            return value == null || value.length() <= max ? value : value.substring(0, max);
        }

        VendorStatus statusOrNull() {
            return status == null ? null : VendorStatus.valueOf(status);
        }

        VendorRequest toRequest() {
            return new VendorRequest(companyName, contactName, email, phone, category, notes);
        }

        /** The file's non-empty cells over the vendor's current values (empty cells keep what is there). */
        VendorRequest mergedInto(Vendor v) {
            return new VendorRequest(companyName, contactName != null ? contactName : v.getContactName(),
                    email != null ? email : v.getEmail(), phone != null ? phone : v.getPhone(),
                    category != null ? category : v.getCategory(), notes != null ? notes : v.getNotes());
        }

        ImportPreview.Row toPreviewRow() {
            return new ImportPreview.Row(rowNumber, companyName, action, changes, errors);
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rowNumber", rowNumber);
            m.put("companyName", companyName);
            m.put("action", action.name());
            m.put("changes", changes);
            m.put("errors", errors.stream().map(e -> Map.of("field", e.field(), "message", e.message())).toList());
            m.put("vendorId", vendorId == null ? null : vendorId.toString());
            m.put("contactName", contactName);
            m.put("email", email);
            m.put("phone", phone);
            m.put("category", category);
            m.put("notes", notes);
            m.put("status", status);
            return m;
        }

        @SuppressWarnings("unchecked")
        static Stored fromMap(Map<String, Object> m) {
            List<ImportPreview.RowError> errors = ((List<Map<String, Object>>) m.get("errors")).stream()
                    .map(e -> new ImportPreview.RowError((String) e.get("field"), (String) e.get("message")))
                    .toList();
            return new Stored(((Number) m.get("rowNumber")).intValue(), (String) m.get("companyName"),
                    ImportRowAction.valueOf((String) m.get("action")), (List<String>) m.get("changes"), errors,
                    m.get("vendorId") == null ? null : UUID.fromString((String) m.get("vendorId")),
                    (String) m.get("contactName"), (String) m.get("email"), (String) m.get("phone"),
                    (String) m.get("category"), (String) m.get("notes"), (String) m.get("status"));
        }
    }
}
