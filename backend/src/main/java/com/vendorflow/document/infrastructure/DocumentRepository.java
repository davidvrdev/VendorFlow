package com.vendorflow.document.infrastructure;

import com.vendorflow.document.domain.Document;
import com.vendorflow.document.domain.DocumentState;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Every method takes the organization id: a document of another organization is simply never found. */
public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Optional<Document> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** Row lock for state changes (review/archive/dates) so they serialize with a concurrent supersede. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Document d where d.id = :id and d.organizationId = :organizationId")
    Optional<Document> findForUpdate(@Param("id") UUID id, @Param("organizationId") UUID organizationId);

    /** The CURRENT document of a requirement, locked: the upload that supersedes it must be the only writer. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select d from Document d
            where d.organizationId = :organizationId and d.vendorId = :vendorId
              and d.documentTypeId = :documentTypeId and d.state = :state
            """)
    Optional<Document> findCurrentForUpdate(@Param("organizationId") UUID organizationId, @Param("vendorId") UUID vendorId,
            @Param("documentTypeId") UUID documentTypeId, @Param("state") DocumentState state);

    @Query("""
            select new com.vendorflow.document.infrastructure.DocumentRow(d, t)
            from Document d join DocumentType t on t.id = d.documentTypeId and t.organizationId = d.organizationId
            where d.id = :id and d.organizationId = :organizationId
            """)
    Optional<DocumentRow> findRow(@Param("id") UUID id, @Param("organizationId") UUID organizationId);

    /** Ordered by type sortOrder, then newest upload first. The Pageable only bounds the number of rows. */
    @Query("""
            select new com.vendorflow.document.infrastructure.DocumentRow(d, t)
            from Document d join DocumentType t on t.id = d.documentTypeId and t.organizationId = d.organizationId
            where d.organizationId = :organizationId and d.vendorId = :vendorId and d.state in :states
            order by t.sortOrder asc, t.name asc, d.createdAt desc, d.id desc
            """)
    List<DocumentRow> findRows(@Param("organizationId") UUID organizationId, @Param("vendorId") UUID vendorId,
            @Param("states") Collection<DocumentState> states, Pageable limit);
}
