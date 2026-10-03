package com.vendorflow.vendor.infrastructure;

import com.vendorflow.vendor.domain.VendorRequirement;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VendorRequirementRepository extends JpaRepository<VendorRequirement, UUID> {

    List<VendorRequirement> findByOrganizationIdAndVendorId(UUID organizationId, UUID vendorId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from VendorRequirement r
            where r.organizationId = :organizationId and r.vendorId = :vendorId
              and r.documentTypeId in :documentTypeIds
            """)
    int deleteByType(@Param("organizationId") UUID organizationId, @Param("vendorId") UUID vendorId,
            @Param("documentTypeIds") Collection<UUID> documentTypeIds);
}
