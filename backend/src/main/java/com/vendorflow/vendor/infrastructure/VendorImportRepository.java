package com.vendorflow.vendor.infrastructure;

import com.vendorflow.vendor.domain.VendorImport;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Every lookup takes the organization id: an import of another organization is simply never found. */
public interface VendorImportRepository extends JpaRepository<VendorImport, UUID> {

    /** Row lock: two concurrent commits of the same import run one after the other (the second sees COMMITTED). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from VendorImport i where i.id = :id and i.organizationId = :organizationId")
    Optional<VendorImport> findForUpdate(@Param("id") UUID id, @Param("organizationId") UUID organizationId);

    @Modifying
    @Query("delete from VendorImport i where i.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
