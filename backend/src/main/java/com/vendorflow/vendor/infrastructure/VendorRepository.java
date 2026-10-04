package com.vendorflow.vendor.infrastructure;

import com.vendorflow.vendor.domain.Vendor;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Every method takes the organization id: a vendor of another organization is simply never found. */
public interface VendorRepository extends JpaRepository<Vendor, UUID> {

    Optional<Vendor> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** Serializes concurrent changes of one vendor requirements (two inserts of the same pair would hit the unique index). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Vendor v where v.id = :id and v.organizationId = :organizationId")
    Optional<Vendor> findForUpdate(@Param("id") UUID id, @Param("organizationId") UUID organizationId);

    @Query("""
            select count(v) > 0 from Vendor v
            where v.organizationId = :organizationId and lower(v.companyName) = lower(:companyName)
            """)
    boolean existsByName(@Param("organizationId") UUID organizationId, @Param("companyName") String companyName);

    @Query("""
            select count(v) > 0 from Vendor v
            where v.organizationId = :organizationId and lower(v.companyName) = lower(:companyName)
              and v.id <> :excludeId
            """)
    boolean existsByNameExcluding(@Param("organizationId") UUID organizationId,
            @Param("companyName") String companyName, @Param("excludeId") UUID excludeId);

    /**
     * Same match, row-locked (CSV commit). Ordered by id so two commits always lock in the same order and cannot
     * deadlock; a concurrent vendor edit waits for the commit, or the commit sees the edit.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Vendor v where v.organizationId = :organizationId and lower(v.companyName) in :lowerNames"
            + " order by v.id")
    List<Vendor> findByLowerNamesForUpdate(@Param("organizationId") UUID organizationId,
            @Param("lowerNames") java.util.Collection<String> lowerNames);

    /** Existing vendors whose lower-cased name is in {@code lowerNames} (CSV import matching; callers pass at most a few thousand). */
    @Query("select v from Vendor v where v.organizationId = :organizationId and lower(v.companyName) in :lowerNames")
    List<Vendor> findByLowerNames(@Param("organizationId") UUID organizationId,
            @Param("lowerNames") java.util.Collection<String> lowerNames);

    @Query("""
            select distinct v.category from Vendor v
            where v.organizationId = :organizationId and v.category is not null and v.category <> ''
            """)
    List<String> findCategories(@Param("organizationId") UUID organizationId);
}
