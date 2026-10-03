package com.vendorflow.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Insert/read only. Audit rows are never updated or deleted by application code. */
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    List<AuditEvent> findByOrganizationIdAndActionOrderByCreatedAtAsc(UUID organizationId, String action);
}
