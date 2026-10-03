package com.vendorflow.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Insert/read only. Audit rows are never updated or deleted by application code. */
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    List<AuditEvent> findByOrganizationIdAndActionOrderByCreatedAtAsc(UUID organizationId, String action);

    /**
     * Events of one entity, newest first, with the actor's name in the SAME query (no N+1). Joining app_user here is
     * the accepted read-model pattern (docs/PROJECT_STATE technical debt); left join: the actor may be null.
     */
    @Query(value = """
            select new com.vendorflow.audit.AuditEntry(e.id, e.action, u.fullName, e.createdAt, e.metadata)
            from AuditEvent e left join com.vendorflow.identity.domain.AppUser u on u.id = e.actorUserId
            where e.organizationId = :organizationId and e.entityType = :entityType and e.entityId = :entityId
            order by e.createdAt desc, e.id desc
            """,
            countQuery = """
                    select count(e) from AuditEvent e
                    where e.organizationId = :organizationId and e.entityType = :entityType
                      and e.entityId = :entityId
                    """)
    Page<AuditEntry> findEntries(@Param("organizationId") UUID organizationId,
            @Param("entityType") String entityType, @Param("entityId") UUID entityId, Pageable pageable);
}
