package com.vendorflow.organization.infrastructure;

import com.vendorflow.organization.domain.Membership;
import com.vendorflow.organization.domain.OrganizationSummary;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    /** Membership + organization in one query: used on every request by TenantContextFilter. */
    @Query("""
            select new com.vendorflow.organization.domain.OrganizationSummary(o.id, o.name, m.role)
            from Membership m join Organization o on o.id = m.organizationId
            where m.userId = :userId and m.organizationId = :organizationId
            """)
    Optional<OrganizationSummary> findSummary(@Param("userId") UUID userId, @Param("organizationId") UUID organizationId);

    @Query("""
            select new com.vendorflow.organization.domain.OrganizationSummary(o.id, o.name, m.role)
            from Membership m join Organization o on o.id = m.organizationId
            where m.userId = :userId
            """)
    List<OrganizationSummary> findAllSummariesByUserId(@Param("userId") UUID userId);

    Optional<Membership> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);
}
