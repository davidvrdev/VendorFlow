package com.vendorflow.vendor.application;

import com.vendorflow.audit.AuditService;
import com.vendorflow.compliance.application.ComplianceContext;
import com.vendorflow.compliance.application.ComplianceContextService;
import com.vendorflow.compliance.domain.ComplianceCalculator;
import com.vendorflow.compliance.domain.ComplianceCalculator.DocState;
import com.vendorflow.compliance.domain.RequirementStatus;
import com.vendorflow.compliance.domain.VendorCompliance;
import com.vendorflow.document.api.DocumentTypeAdminView;
import com.vendorflow.document.api.DocumentSummary;
import com.vendorflow.document.api.DocumentTypeView;
import com.vendorflow.document.application.DocumentReadService;
import com.vendorflow.document.application.DocumentTypeService;
import com.vendorflow.identity.application.UserAccountService;
import com.vendorflow.organization.application.AuthorizationService;
import com.vendorflow.organization.application.TenantContext;
import com.vendorflow.organization.domain.Permission;
import com.vendorflow.shared.error.ApiException;
import com.vendorflow.shared.error.FieldViolation;
import com.vendorflow.shared.error.NotFoundException;
import com.vendorflow.shared.error.RequestValidationException;
import com.vendorflow.shared.web.PageResponse;
import com.vendorflow.vendor.api.RequirementsRequest;
import com.vendorflow.vendor.api.VendorDetail;
import com.vendorflow.vendor.api.VendorRequest;
import com.vendorflow.vendor.api.VendorSummary;
import com.vendorflow.vendor.domain.Vendor;
import com.vendorflow.vendor.domain.VendorRequirement;
import com.vendorflow.vendor.domain.VendorStatus;
import com.vendorflow.vendor.infrastructure.VendorRepository;
import com.vendorflow.vendor.infrastructure.VendorRequirementRepository;
import com.vendorflow.vendor.infrastructure.VendorSearchRepository;
import com.vendorflow.vendor.infrastructure.VendorSearchRepository.SortField;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vendor use cases. Every method resolves the organization from TenantContext (via AuthorizationService), loads
 * vendors only by (id, organizationId) and writes its audit event in the same transaction.
 */
@Service
public class VendorService {

    public static final String ENTITY_TYPE = "vendor";
    public static final int DEFAULT_PAGE_SIZE = 25;
    private static final String NAME_UNIQUE_INDEX = "vendor_org_company_name_lower_uq";

    private final VendorRepository vendors;
    private final VendorRequirementRepository requirements;
    private final VendorSearchRepository search;
    private final DocumentTypeService documentTypes;
    private final DocumentReadService documentReads;
    private final UserAccountService users;
    private final AuthorizationService authorization;
    private final AuditService audit;
    private final Clock clock;
    private final ComplianceContextService complianceContext;

    public VendorService(VendorRepository vendors, VendorRequirementRepository requirements,
            VendorSearchRepository search, DocumentTypeService documentTypes, DocumentReadService documentReads,
            UserAccountService users,
            AuthorizationService authorization, AuditService audit, Clock clock,
            ComplianceContextService complianceContext) {
        this.vendors = vendors;
        this.requirements = requirements;
        this.search = search;
        this.documentTypes = documentTypes;
        this.documentReads = documentReads;
        this.users = users;
        this.authorization = authorization;
        this.audit = audit;
        this.clock = clock;
        this.complianceContext = complianceContext;
    }

    // ---- reads ----

    @Transactional(readOnly = true)
    public PageResponse<VendorSummary> list(String q, String status, String category, String compliance, String sort,
            int page, int size) {
        TenantContext.Tenant tenant = authorization.require(Permission.DATA_VIEW);
        List<FieldViolation> errors = new ArrayList<>();
        VendorStatus statusFilter = parseStatus(status, errors);
        VendorCompliance complianceFilter = parseCompliance(compliance, errors);
        SortSpec sortSpec = parseSort(sort, errors);
        if (!errors.isEmpty()) {
            throw new RequestValidationException(errors);
        }
        ComplianceContext ctx = complianceContext.forOrganization(tenant.organizationId());
        var criteria = new VendorSearchRepository.Criteria(blankToNull(q), statusFilter, blankToNull(category),
                complianceFilter, sortSpec.field(), sortSpec.ascending(), ctx.today(), ctx.windowDays());
        return PageResponse.of(search.search(tenant.organizationId(), criteria, PageResponse.pageable(page, size)));
    }

    @Transactional(readOnly = true)
    public List<String> categories() {
        TenantContext.Tenant tenant = authorization.require(Permission.DATA_VIEW);
        // Case-insensitive de-duplication ("Plumbing" and "plumbing" would be one filter entry; the filter itself is
        // case-insensitive), sorted alphabetically ignoring case.
        Map<String, String> byLowerCase = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        vendors.findCategories(tenant.organizationId()).stream().sorted().forEach(c -> byLowerCase.putIfAbsent(c, c));
        return List.copyOf(byLowerCase.values());
    }

    @Transactional(readOnly = true)
    public VendorDetail get(UUID id) {
        TenantContext.Tenant tenant = authorization.require(Permission.DATA_VIEW);
        return detail(load(tenant.organizationId(), id));
    }

    // ---- writes ----

    @Transactional
    public VendorDetail create(VendorRequest request) {
        TenantContext.Tenant tenant = authorization.require(Permission.CONTENT_WRITE);
        UUID orgId = tenant.organizationId();
        if (vendors.existsByName(orgId, request.companyName())) {
            throw duplicate();
        }
        Instant now = clock.instant();
        Vendor vendor = new Vendor(orgId, tenant.userId(), now);
        vendor.setDetails(request.companyName(), request.contactName(), request.email(), request.phone(),
                request.category(), request.notes());
        saveUnique(vendor);

        List<DocumentTypeView> defaults = documentTypes.findActiveRequiredByDefault(orgId);
        requirements.saveAll(defaults.stream()
                .map(t -> new VendorRequirement(orgId, vendor.getId(), t.id(), now)).toList());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("companyName", vendor.getCompanyName());
        metadata.put("requirements", defaults.stream().map(DocumentTypeView::code).toList());
        audit.record("vendor.created", ENTITY_TYPE, vendor.getId(), metadata);
        return detail(vendor);
    }

    /** Full replacement of the editable fields. A request that changes nothing writes no audit event. */
    @Transactional
    public VendorDetail update(UUID id, VendorRequest request) {
        TenantContext.Tenant tenant = authorization.require(Permission.CONTENT_WRITE);
        Vendor vendor = load(tenant.organizationId(), id);

        Map<String, Object> changes = new LinkedHashMap<>();
        diff(changes, "companyName", vendor.getCompanyName(), request.companyName());
        diff(changes, "contactName", vendor.getContactName(), request.contactName());
        diff(changes, "email", vendor.getEmail(), request.email());
        diff(changes, "phone", vendor.getPhone(), request.phone());
        diff(changes, "category", vendor.getCategory(), request.category());
        diff(changes, "notes", vendor.getNotes(), request.notes());
        if (changes.isEmpty()) {
            return detail(vendor);
        }
        if (changes.containsKey("companyName")
                && vendors.existsByNameExcluding(tenant.organizationId(), request.companyName(), id)) {
            throw duplicate();
        }
        vendor.setDetails(request.companyName(), request.contactName(), request.email(), request.phone(),
                request.category(), request.notes());
        vendor.touch(clock.instant());
        saveUnique(vendor);
        audit.record("vendor.updated", ENTITY_TYPE, id, Map.of("changes", changes));
        return detail(vendor);
    }

    @Transactional
    public VendorDetail deactivate(UUID id) {
        return changeStatus(id, VendorStatus.INACTIVE, "vendor.deactivated");
    }

    @Transactional
    public VendorDetail reactivate(UUID id) {
        return changeStatus(id, VendorStatus.ACTIVE, "vendor.reactivated");
    }

    /** Full replacement of the vendor required document types. */
    @Transactional
    public VendorDetail updateRequirements(UUID id, RequirementsRequest request) {
        TenantContext.Tenant tenant = authorization.require(Permission.REQUIREMENTS_MANAGE);
        UUID orgId = tenant.organizationId();
        // Row lock: two concurrent replacements of the same vendor run one after the other.
        Vendor vendor = vendors.findForUpdate(id, orgId).orElseThrow(VendorService::notFound);

        Set<UUID> wanted = new LinkedHashSet<>(request.documentTypeIds());
        Map<UUID, DocumentTypeView> active = documentTypes.findActiveByIds(orgId, wanted).stream()
                .collect(Collectors.toMap(DocumentTypeView::id, t -> t));
        // Same answer for "belongs to another organization", "does not exist" and "inactive": no existence oracle.
        if (active.size() != wanted.size()) {
            throw new RequestValidationException("documentTypeIds", "must all be active document types");
        }

        Set<UUID> current = requirements.findByOrganizationIdAndVendorId(orgId, id).stream()
                .map(VendorRequirement::getDocumentTypeId).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<UUID> toAdd = new LinkedHashSet<>(wanted);
        toAdd.removeAll(current);
        Set<UUID> toRemove = new LinkedHashSet<>(current);
        toRemove.removeAll(wanted);
        if (toAdd.isEmpty() && toRemove.isEmpty()) {
            return detail(vendor);
        }

        Instant now = clock.instant();
        List<String> removedCodes = documentTypes.findByIds(orgId, toRemove).stream().map(DocumentTypeView::code)
                .sorted().toList();
        if (!toRemove.isEmpty()) {
            requirements.deleteByType(orgId, id, toRemove);
        }
        requirements.saveAll(toAdd.stream().map(typeId -> new VendorRequirement(orgId, id, typeId, now)).toList());
        // The bulk delete clears the persistence context: re-attach the vendor to touch it.
        Vendor reloaded = vendors.findByIdAndOrganizationId(id, orgId).orElseThrow(VendorService::notFound);
        reloaded.touch(now);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("added", toAdd.stream().map(t -> active.get(t).code()).sorted().toList());
        metadata.put("removed", removedCodes);
        audit.record("vendor.requirements_changed", ENTITY_TYPE, id, metadata);
        return detail(reloaded);
    }

    // ---- used by VendorHistoryService (same feature) ----

    /** Authorizes the vendor read and proves the vendor belongs to the caller organization (404 otherwise). */
    @Transactional(readOnly = true)
    public UUID requireViewable(UUID id) {
        TenantContext.Tenant tenant = authorization.require(Permission.DATA_VIEW);
        load(tenant.organizationId(), id);
        return tenant.organizationId();
    }

    // ---- helpers ----

    private VendorDetail changeStatus(UUID id, VendorStatus target, String action) {
        TenantContext.Tenant tenant = authorization.require(Permission.ARCHIVE_AND_IMPORT);
        Vendor vendor = load(tenant.organizationId(), id);
        if (vendor.getStatus() != target) {
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("before", vendor.getStatus().name());
            change.put("after", target.name());
            vendor.setStatus(target);
            vendor.touch(clock.instant());
            audit.record(action, ENTITY_TYPE, id, Map.of("changes", Map.of("status", change)));
        }
        return detail(vendor);
    }

    private Vendor load(UUID organizationId, UUID id) {
        return vendors.findByIdAndOrganizationId(id, organizationId).orElseThrow(VendorService::notFound);
    }

    private static NotFoundException notFound() {
        return new NotFoundException("Vendor not found.");
    }

    private static ApiException duplicate() {
        return new ApiException(HttpStatus.CONFLICT, "vendor-exists", "Vendor already exists",
                "A vendor with this company name already exists.");
    }

    /** Flushes now so a lost race on the unique index surfaces here as the same 409 as the pre-check. */
    private void saveUnique(Vendor vendor) {
        try {
            vendors.saveAndFlush(vendor);
        } catch (DataIntegrityViolationException e) {
            // Only the name index means "duplicate"; any other violation is a bug and must stay a 500.
            Throwable cause = e.getMostSpecificCause();
            if (cause.getMessage() != null && cause.getMessage().contains(NAME_UNIQUE_INDEX)) {
                throw duplicate();
            }
            throw e;
        }
    }

    private VendorDetail detail(Vendor v) {
        UUID orgId = v.getOrganizationId();
        ComplianceContext ctx = complianceContext.forOrganization(orgId);
        List<UUID> typeIds = requirements.findByOrganizationIdAndVendorId(orgId, v.getId()).stream()
                .map(VendorRequirement::getDocumentTypeId).toList();
        // ONE query for all CURRENT documents of the vendor (at most one per type), then matched in memory.
        Map<UUID, DocumentSummary> currentByType = new LinkedHashMap<>();
        for (DocumentSummary doc : documentReads.findByVendor(orgId, v.getId(), false)) {
            currentByType.put(doc.documentType().id(), doc);
        }
        List<DocumentTypeAdminView> types = documentTypes.findAdminViewsByIds(orgId, typeIds).stream()
                .sorted(Comparator.comparingInt(DocumentTypeAdminView::sortOrder)
                        .thenComparing(DocumentTypeAdminView::name))
                .toList();
        // Rules live in ComplianceCalculator; everything it needs is already loaded (no per-requirement queries).
        // Requirements of inactive types still get a status/days (shown greyed) but never count in the summary.
        List<VendorDetail.Requirement> reqs = new ArrayList<>();
        List<ComplianceCalculator.Evaluated> activeEvaluations = new ArrayList<>();
        for (DocumentTypeAdminView t : types) {
            DocumentSummary doc = currentByType.get(t.id());
            Optional<DocState> state = Optional.ofNullable(doc)
                    .map(d -> new DocState(d.reviewStatus(), d.expirationDate()));
            ComplianceCalculator.Evaluated evaluated = ComplianceCalculator.evaluated(t.hasExpiration(), state,
                    ctx.today(), ctx.windowDays());
            if (t.active()) {
                activeEvaluations.add(evaluated);
            }
            reqs.add(new VendorDetail.Requirement(t.id(), t.code(), t.name(), t.hasExpiration(), doc,
                    evaluated.status(),
                    ComplianceCalculator.daysUntilExpiration(t.hasExpiration(), state, ctx.today()), t.active()));
        }
        Set<UUID> requiredTypeIds = new HashSet<>(typeIds);
        List<DocumentSummary> others = currentByType.values().stream()
                .filter(doc -> !requiredTypeIds.contains(doc.documentType().id())).toList();
        VendorDetail.CreatedBy createdBy = v.getCreatedByUserId() == null ? null
                : new VendorDetail.CreatedBy(users.require(v.getCreatedByUserId()).fullName());
        return new VendorDetail(v.getId(), v.getCompanyName(), v.getContactName(), v.getEmail(), v.getPhone(),
                v.getCategory(), v.getStatus(), activeEvaluations.size(), v.getCreatedAt(), v.getUpdatedAt(),
                v.getNotes(), createdBy, reqs, others, ComplianceCalculator.summarize(activeEvaluations, ctx.today()));
    }

    // ---- used by the document feature (it authorizes the caller itself, then asks us about the vendor) ----

    /** @throws NotFoundException (404) unless the vendor exists in {@code organizationId} */
    @Transactional(readOnly = true)
    public void requireExists(UUID organizationId, UUID vendorId) {
        load(organizationId, vendorId);
    }

    /**
     * Locks the vendor row ({@code FOR UPDATE}) inside the caller's transaction: uploads for one vendor run one after
     * the other, so "supersede the CURRENT document" cannot race with another upload.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireExistsAndLock(UUID organizationId, UUID vendorId) {
        vendors.findForUpdate(vendorId, organizationId).orElseThrow(VendorService::notFound);
    }

    private static void diff(Map<String, Object> changes, String field, String before, String after) {
        if (before == null ? after != null : !before.equals(after)) {
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("before", before);
            change.put("after", after);
            changes.put(field, change);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static VendorStatus parseStatus(String status, List<FieldViolation> errors) {
        if (status == null || status.isBlank() || status.equals("ACTIVE")) {
            return VendorStatus.ACTIVE;
        }
        return switch (status) {
            case "INACTIVE" -> VendorStatus.INACTIVE;
            case "ALL" -> null;
            default -> {
                errors.add(new FieldViolation("status", "must be ACTIVE, INACTIVE or ALL"));
                yield VendorStatus.ACTIVE;
            }
        };
    }

    private static VendorCompliance parseCompliance(String compliance, List<FieldViolation> errors) {
        if (compliance == null || compliance.isBlank()) {
            return null;
        }
        try {
            return VendorCompliance.valueOf(compliance);
        } catch (IllegalArgumentException e) {
            errors.add(new FieldViolation("compliance", "must be COMPLIANT, ATTENTION or NON_COMPLIANT"));
            return null;
        }
    }

    private record SortSpec(SortField field, boolean ascending) {
    }

    private static SortSpec parseSort(String sort, List<FieldViolation> errors) {
        if (sort == null || sort.isBlank()) {
            return new SortSpec(SortField.COMPANY_NAME, true);
        }
        String[] parts = sort.split(",", -1);
        SortField field = parts.length > 2 ? null : switch (parts[0]) {
            case "companyName" -> SortField.COMPANY_NAME;
            case "createdAt" -> SortField.CREATED_AT;
            case "updatedAt" -> SortField.UPDATED_AT;
            case "compliance" -> SortField.COMPLIANCE;
            case "nextExpiration" -> SortField.NEXT_EXPIRATION;
            default -> null;
        };
        String direction = parts.length == 2 ? parts[1].toLowerCase(Locale.ROOT) : "asc";
        if (field == null || !(direction.equals("asc") || direction.equals("desc"))) {
            errors.add(new FieldViolation("sort", "must be companyName, createdAt, updatedAt, compliance or nextExpiration,"
                    + " optionally followed by ,asc or ,desc"));
            return new SortSpec(SortField.COMPANY_NAME, true);
        }
        return new SortSpec(field, direction.equals("asc"));
    }
}
