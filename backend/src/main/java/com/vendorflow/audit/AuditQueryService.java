package com.vendorflow.audit;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the audit log. Callers MUST have authorized the read of the entity itself (e.g. loaded it by id and
 * organization) before asking for its events: this service only scopes by the organization id it is given.
 */
@Service
public class AuditQueryService {

    private final AuditEventRepository repository;

    public AuditQueryService(AuditEventRepository repository) {
        this.repository = repository;
    }

    /** Events of one entity of the organization, newest first (pageable's sort is ignored on purpose). */
    @Transactional(readOnly = true)
    public Page<AuditEntry> forEntity(UUID organizationId, String entityType, UUID entityId, Pageable pageable) {
        return repository.findEntries(organizationId, entityType, entityId, pageable);
    }
}
