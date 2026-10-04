package com.vendorflow.organization.infrastructure;

import com.vendorflow.organization.domain.MemberView;
import com.vendorflow.organization.domain.Membership;
import com.vendorflow.organization.domain.OrganizationSummary;
import com.vendorflow.organization.domain.StaffRecipient;
import com.vendorflow.shared.tenant.Role;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    /** Tenant-scoped lookup: a membership id of another organization simply is not found. */
    Optional<Membership> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /**
     * Member list: membership joined with the user in ONE query (no N+1), sorted by name. This read model joins the
     * identity table on purpose; the alternative (two queries) cannot sort or page by the user's name.
     */
    @Query(value = """
            select new com.vendorflow.organization.domain.MemberView(
                m.id, u.id, u.fullName, u.email, m.role, m.createdAt)
            from Membership m join AppUser u on u.id = m.userId
            where m.organizationId = :organizationId
            order by lower(u.fullName), u.email, m.id
            """,
            countQuery = "select count(m) from Membership m where m.organizationId = :organizationId")
    Page<MemberView> findMembers(@Param("organizationId") UUID organizationId, Pageable pageable);

    @Query("""
            select new com.vendorflow.organization.domain.MemberView(
                m.id, u.id, u.fullName, u.email, m.role, m.createdAt)
            from Membership m join AppUser u on u.id = m.userId
            where m.organizationId = :organizationId and m.id = :membershipId
            """)
    Optional<MemberView> findMember(@Param("organizationId") UUID organizationId,
            @Param("membershipId") UUID membershipId);

    /**
     * Locks the organization's rows of one role ({@code FOR NO KEY UPDATE}) in a deterministic order. Taken BEFORE
     * counting owners, it serializes concurrent role changes/removals so two owners cannot demote each other at the
     * same time and leave the organization without an owner.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Membership m where m.organizationId = :organizationId and m.role = :role order by m.id")
    List<Membership> lockByRole(@Param("organizationId") UUID organizationId, @Param("role") Role role);

    @Query("""
            select count(m) from Membership m join AppUser u on u.id = m.userId
            where m.organizationId = :organizationId and lower(u.email) = lower(:email)
            """)
    long countByOrganizationAndEmail(@Param("organizationId") UUID organizationId, @Param("email") String email);

    /** Verified OWNERs and ADMINs, the people operational notifications go to (same rule as the reminder digest). */
    @Query("""
            select new com.vendorflow.organization.domain.StaffRecipient(u.id, u.email)
            from Membership m join AppUser u on u.id = m.userId
            where m.organizationId = :organizationId and m.role in (com.vendorflow.shared.tenant.Role.OWNER,
                  com.vendorflow.shared.tenant.Role.ADMIN) and u.emailVerifiedAt is not null
            order by lower(u.email), u.id
            """)
    List<StaffRecipient> findVerifiedStaffRecipients(@Param("organizationId") UUID organizationId);
}
