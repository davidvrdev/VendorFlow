package com.vendorflow.document.application;

import com.vendorflow.audit.AuditService;
import com.vendorflow.document.api.DocumentTypeAdminView;
import com.vendorflow.document.api.DocumentTypeCreateRequest;
import com.vendorflow.document.api.DocumentTypeUpdateRequest;
import com.vendorflow.document.api.DocumentTypeView;
import com.vendorflow.document.domain.DocumentType;
import com.vendorflow.document.infrastructure.DocumentTypeRepository;
import com.vendorflow.organization.application.AuthorizationService;
import com.vendorflow.organization.application.TenantContext;
import com.vendorflow.organization.domain.OrganizationCreatedEvent;
import com.vendorflow.organization.domain.Permission;
import com.vendorflow.shared.error.ApiException;
import com.vendorflow.shared.error.NotFoundException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentTypeService {

    public static final String ENTITY_TYPE = "document_type";
    private static final String CODE_PREFIX = "CUSTOM_";
    private static final String NAME_UNIQUE_INDEX = "document_type_org_name_lower_uq";
    // The code derives from the name, so two concurrent creates of the same name can hit the code index first.
    // (Two different names with the same slug racing each other also land here: a rare, retryable 409.)
    private static final String CODE_UNIQUE_INDEX = "document_type_org_code_uq";
    private static final SecureRandom RANDOM = new SecureRandom();

    private record Default(String code, String name, boolean hasExpiration, boolean requiredByDefault, int sortOrder) {
    }

    /** Keep in sync with docs/DATABASE.md and the backfill in V4__document_types_and_vendors.sql. */
    private static final List<Default> DEFAULTS = List.of(
            new Default("COI", "Certificate of Insurance", true, true, 10),
            new Default("GENERAL_LIABILITY", "General Liability", true, false, 20),
            new Default("WORKERS_COMP", "Workers' Compensation", true, true, 30),
            new Default("BUSINESS_LICENSE", "Business License", true, false, 40),
            new Default("PROFESSIONAL_LICENSE", "Professional License", true, false, 50),
            new Default("W9", "W-9", false, true, 60),
            new Default("CONTRACT", "Contract", false, false, 70),
            new Default("OTHER", "Other", false, false, 80));

    private final DocumentTypeRepository types;
    private final AuthorizationService authorization;
    private final AuditService audit;
    private final Clock clock;

    public DocumentTypeService(DocumentTypeRepository types, AuthorizationService authorization, AuditService audit,
            Clock clock) {
        this.types = types;
        this.authorization = authorization;
        this.audit = audit;
        this.clock = clock;
    }

    /** Active types of the caller's organization, by sortOrder. Any role may read them. */
    @Transactional(readOnly = true)
    public List<DocumentTypeView> listActive() {
        TenantContext.Tenant tenant = authorization.require(Permission.DATA_VIEW);
        return types.findByOrganizationIdAndActiveTrueOrderBySortOrderAscNameAsc(tenant.organizationId()).stream()
                .map(DocumentTypeView::from).toList();
    }

    /**
     * Synchronous listener: runs inside the transaction that creates the organization (the publisher's), so a seeding
     * failure rolls the signup back. Deliberately NOT @TransactionalEventListener(AFTER_COMMIT).
     */
    @EventListener
    public void onOrganizationCreated(OrganizationCreatedEvent event) {
        seedDefaults(event.organizationId());
    }

    /** Idempotent: does nothing if the organization already has any document type. MANDATORY: never seed half-way. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void seedDefaults(UUID organizationId) {
        if (types.existsByOrganizationId(organizationId)) {
            return;
        }
        Instant now = clock.instant();
        types.saveAll(DEFAULTS.stream().map(d -> new DocumentType(organizationId, d.code(), d.name(),
                d.hasExpiration(), d.requiredByDefault(), d.sortOrder(), now)).toList());
    }

    /** Active types of the organization that new vendors are required to provide. */
    @Transactional(readOnly = true)
    public List<DocumentTypeView> findActiveRequiredByDefault(UUID organizationId) {
        return types.findByOrganizationIdAndActiveTrueAndRequiredByDefaultTrueOrderBySortOrderAsc(organizationId)
                .stream().map(DocumentTypeView::from).toList();
    }

    /** The subset of {@code ids} that are ACTIVE types of the organization (foreign/unknown/inactive ids are absent). */
    @Transactional(readOnly = true)
    public List<DocumentTypeView> findActiveByIds(UUID organizationId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return types.findByOrganizationIdAndActiveTrueAndIdIn(organizationId, ids).stream()
                .map(DocumentTypeView::from).toList();
    }

    /** Like findActiveByIds but regardless of active (to describe requirements that point at a later-deactivated type). */
    @Transactional(readOnly = true)
    public List<DocumentTypeView> findByIds(UUID organizationId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return types.findByOrganizationIdAndIdIn(organizationId, ids).stream().map(DocumentTypeView::from).toList();
    }

    /** Like findByIds, with {@code active} (vendor detail shows requirements of deactivated types greyed out). */
    @Transactional(readOnly = true)
    public List<DocumentTypeAdminView> findAdminViewsByIds(UUID organizationId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return types.findByOrganizationIdAndIdIn(organizationId, ids).stream().map(DocumentTypeAdminView::from)
                .toList();
    }

    // ---- management (Phase 3) ----

    /** All types of the organization (active and inactive) with {@code active}: management screens only. */
    @Transactional(readOnly = true)
    public List<DocumentTypeAdminView> listAll() {
        TenantContext.Tenant tenant = authorization.require(Permission.REQUIREMENTS_MANAGE);
        return types.findByOrganizationIdOrderBySortOrderAscNameAsc(tenant.organizationId()).stream()
                .map(DocumentTypeAdminView::from).toList();
    }

    @Transactional
    public DocumentTypeAdminView create(DocumentTypeCreateRequest request) {
        TenantContext.Tenant tenant = authorization.require(Permission.REQUIREMENTS_MANAGE);
        UUID orgId = tenant.organizationId();
        if (types.existsByName(orgId, request.name())) {
            throw duplicate();
        }
        DocumentType type = new DocumentType(orgId, uniqueCode(orgId, request.name()), request.name(),
                request.hasExpiration(), request.requiredByDefault(), types.maxSortOrder(orgId) + 10,
                clock.instant());
        saveUnique(type);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("code", type.getCode());
        metadata.put("name", type.getName());
        metadata.put("hasExpiration", type.isHasExpiration());
        metadata.put("requiredByDefault", type.isRequiredByDefault());
        audit.record("document_type.created", ENTITY_TYPE, type.getId(), metadata);
        return DocumentTypeAdminView.from(type);
    }

    /** Partial update. The code is immutable. A request that changes nothing writes no audit event. */
    @Transactional
    public DocumentTypeAdminView update(UUID id, DocumentTypeUpdateRequest request) {
        TenantContext.Tenant tenant = authorization.require(Permission.REQUIREMENTS_MANAGE);
        DocumentType type = types.findByIdAndOrganizationId(id, tenant.organizationId())
                .orElseThrow(() -> new NotFoundException("Document type not found."));

        String name = request.name() != null ? request.name() : type.getName();
        boolean hasExpiration = request.hasExpiration() != null ? request.hasExpiration() : type.isHasExpiration();
        boolean requiredByDefault = request.requiredByDefault() != null ? request.requiredByDefault()
                : type.isRequiredByDefault();
        boolean active = request.active() != null ? request.active() : type.isActive();
        int sortOrder = request.sortOrder() != null ? request.sortOrder() : type.getSortOrder();

        Map<String, Object> changes = new LinkedHashMap<>();
        diff(changes, "name", type.getName(), name);
        diff(changes, "hasExpiration", type.isHasExpiration(), hasExpiration);
        diff(changes, "requiredByDefault", type.isRequiredByDefault(), requiredByDefault);
        diff(changes, "active", type.isActive(), active);
        diff(changes, "sortOrder", type.getSortOrder(), sortOrder);
        if (changes.isEmpty()) {
            return DocumentTypeAdminView.from(type);
        }
        if (changes.containsKey("name") && types.existsByNameExcluding(tenant.organizationId(), name, id)) {
            throw duplicate();
        }
        type.edit(name, hasExpiration, requiredByDefault, active, sortOrder, clock.instant());
        saveUnique(type);
        audit.record("document_type.updated", ENTITY_TYPE, id, Map.of("changes", changes));
        return DocumentTypeAdminView.from(type);
    }

    /** "CUSTOM_" + upper-case ASCII slug of the name; a short random suffix if that code is taken. */
    private String uniqueCode(UUID orgId, String name) {
        String base = CODE_PREFIX + slug(name);
        String code = base;
        for (int attempt = 0; types.existsByOrganizationIdAndCode(orgId, code); attempt++) {
            if (attempt >= 20) {
                throw new IllegalStateException("Could not generate a unique document type code");
            }
            String suffix = Integer.toHexString(0x10000 + RANDOM.nextInt(0xF0000)).substring(1, 5);
            code = base + "_" + suffix.toUpperCase(Locale.ROOT);
        }
        return code;
    }

    static String slug(String name) {
        String ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String slug = ascii.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (slug.isEmpty()) {
            slug = "TYPE";
        }
        // code column: 50 chars max = prefix + slug + optional "_XXXX" suffix.
        int max = 50 - CODE_PREFIX.length() - 5;
        return slug.length() > max ? slug.substring(0, max).replaceAll("_+$", "") : slug;
    }

    private static void diff(Map<String, Object> changes, String field, Object before, Object after) {
        if (!before.equals(after)) {
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("before", before);
            change.put("after", after);
            changes.put(field, change);
        }
    }

    private static ApiException duplicate() {
        return new ApiException(HttpStatus.CONFLICT, "document-type-exists", "Document type already exists",
                "A document type with this name already exists.");
    }

    /** Flushes now so a lost race on the name index surfaces here as the same 409 as the pre-check. */
    private void saveUnique(DocumentType type) {
        try {
            types.saveAndFlush(type);
        } catch (DataIntegrityViolationException e) {
            String message = e.getMostSpecificCause().getMessage();
            if (message != null && (message.contains(NAME_UNIQUE_INDEX) || message.contains(CODE_UNIQUE_INDEX))) {
                throw duplicate();
            }
            throw e;
        }
    }
}
