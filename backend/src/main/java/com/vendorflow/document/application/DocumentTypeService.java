package com.vendorflow.document.application;

import com.vendorflow.document.api.DocumentTypeView;
import com.vendorflow.document.domain.DocumentType;
import com.vendorflow.document.infrastructure.DocumentTypeRepository;
import com.vendorflow.organization.application.AuthorizationService;
import com.vendorflow.organization.application.TenantContext;
import com.vendorflow.organization.domain.OrganizationCreatedEvent;
import com.vendorflow.organization.domain.Permission;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentTypeService {

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
    private final Clock clock;

    public DocumentTypeService(DocumentTypeRepository types, AuthorizationService authorization, Clock clock) {
        this.types = types;
        this.authorization = authorization;
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
}
