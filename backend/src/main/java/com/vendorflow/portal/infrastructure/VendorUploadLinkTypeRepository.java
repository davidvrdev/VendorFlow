package com.vendorflow.portal.infrastructure;

import com.vendorflow.portal.domain.VendorUploadLinkType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VendorUploadLinkTypeRepository extends JpaRepository<VendorUploadLinkType, UUID> {

    /** One query for a whole page of links (no N+1). */
    List<VendorUploadLinkType> findByOrganizationIdAndLinkIdIn(UUID organizationId, Collection<UUID> linkIds);
}
