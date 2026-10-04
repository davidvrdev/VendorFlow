package com.vendorflow.portal.infrastructure;

import com.vendorflow.portal.domain.VendorUploadLink;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VendorUploadLinkRepository extends JpaRepository<VendorUploadLink, UUID> {

    /** Public portal lookup: the unguessable token (by its hash) IS the capability and determines the organization. */
    @Query("select l from VendorUploadLink l where l.tokenHash = :tokenHash")
    Optional<VendorUploadLink> findByTokenHash(@Param("tokenHash") String tokenHash);

    Optional<VendorUploadLink> findByIdAndOrganizationIdAndVendorId(UUID id, UUID organizationId, UUID vendorId);

    /** Revoke serializes with a concurrent revoke; the use claim is one atomic UPDATE and needs no entity lock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select l from VendorUploadLink l
            where l.id = :id and l.organizationId = :organizationId and l.vendorId = :vendorId
            """)
    Optional<VendorUploadLink> findForUpdate(@Param("id") UUID id, @Param("organizationId") UUID organizationId,
            @Param("vendorId") UUID vendorId);

    @Query(value = """
            select l from VendorUploadLink l
            where l.organizationId = :organizationId and l.vendorId = :vendorId
            order by l.createdAt desc, l.id desc
            """,
            countQuery = "select count(l) from VendorUploadLink l "
                    + "where l.organizationId = :organizationId and l.vendorId = :vendorId")
    Page<VendorUploadLink> findByVendor(@Param("organizationId") UUID organizationId,
            @Param("vendorId") UUID vendorId, Pageable pageable);

    /**
     * Atomically consumes one upload of the link: refused (0 rows) when revoked, expired or a budget (uploads or bytes) is spent, so a
     * revoke or the cap can never be outrun by a concurrent upload. Runs inside the document insert transaction.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update VendorUploadLink l set l.useCount = l.useCount + 1, l.usedBytes = l.usedBytes + :bytes,
                l.lastUsedAt = :now
            where l.organizationId = :organizationId and l.id = :id and l.revokedAt is null
              and l.expiresAt > :now and l.useCount < l.maxUploads
              and l.usedBytes + :bytes <= l.maxTotalBytes
            """)
    int claimUse(@Param("organizationId") UUID organizationId, @Param("id") UUID id, @Param("now") Instant now,
            @Param("bytes") long bytes);
}
