package com.vendorflow.document.infrastructure;

import com.vendorflow.document.domain.DocumentType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentTypeRepository extends JpaRepository<DocumentType, UUID> {

    List<DocumentType> findByOrganizationIdAndActiveTrueOrderBySortOrderAscNameAsc(UUID organizationId);

    List<DocumentType> findByOrganizationIdOrderBySortOrderAscNameAsc(UUID organizationId);

    List<DocumentType> findByOrganizationIdAndActiveTrueAndRequiredByDefaultTrueOrderBySortOrderAsc(UUID organizationId);

    List<DocumentType> findByOrganizationIdAndActiveTrueAndIdIn(UUID organizationId, Collection<UUID> ids);

    List<DocumentType> findByOrganizationIdAndIdIn(UUID organizationId, Collection<UUID> ids);

    Optional<DocumentType> findByIdAndOrganizationId(UUID id, UUID organizationId);

    boolean existsByOrganizationId(UUID organizationId);

    boolean existsByOrganizationIdAndCode(UUID organizationId, String code);

    @Query("""
            select count(t) > 0 from DocumentType t
            where t.organizationId = :organizationId and lower(t.name) = lower(:name)
            """)
    boolean existsByName(@Param("organizationId") UUID organizationId, @Param("name") String name);

    @Query("""
            select count(t) > 0 from DocumentType t
            where t.organizationId = :organizationId and lower(t.name) = lower(:name) and t.id <> :excludeId
            """)
    boolean existsByNameExcluding(@Param("organizationId") UUID organizationId, @Param("name") String name,
            @Param("excludeId") UUID excludeId);

    @Query("select coalesce(max(t.sortOrder), 0) from DocumentType t where t.organizationId = :organizationId")
    int maxSortOrder(@Param("organizationId") UUID organizationId);
}
