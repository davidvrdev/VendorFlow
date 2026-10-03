package com.vendorflow.document.infrastructure;

import com.vendorflow.document.domain.DocumentType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentTypeRepository extends JpaRepository<DocumentType, UUID> {

    List<DocumentType> findByOrganizationIdAndActiveTrueOrderBySortOrderAscNameAsc(UUID organizationId);

    List<DocumentType> findByOrganizationIdAndActiveTrueAndRequiredByDefaultTrueOrderBySortOrderAsc(UUID organizationId);

    List<DocumentType> findByOrganizationIdAndActiveTrueAndIdIn(UUID organizationId, Collection<UUID> ids);

    List<DocumentType> findByOrganizationIdAndIdIn(UUID organizationId, Collection<UUID> ids);

    boolean existsByOrganizationId(UUID organizationId);
}
