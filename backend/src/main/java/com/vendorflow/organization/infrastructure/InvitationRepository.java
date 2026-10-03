package com.vendorflow.organization.infrastructure;

import com.vendorflow.organization.domain.Invitation;
import com.vendorflow.organization.domain.InvitationView;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {

    Optional<Invitation> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** Read-only lookup by token hash (public lookup endpoint). */
    Optional<Invitation> findByTokenHash(String tokenHash);

    /**
     * Row lock: two simultaneous accepts of the same link are serialized, so the loser sees the invitation as
     * already accepted instead of racing to create a second membership.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invitation i where i.tokenHash = :tokenHash")
    Optional<Invitation> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    /** Neither accepted nor revoked (possibly expired): the row the partial unique index would collide with. */
    @Query("""
            select i from Invitation i
            where i.organizationId = :organizationId and lower(i.email) = lower(:email)
              and i.acceptedAt is null and i.revokedAt is null
            """)
    Optional<Invitation> findOpenByEmail(@Param("organizationId") UUID organizationId, @Param("email") String email);

    /** One query: invitations + inviter name (left join: the inviter may have been deleted). */
    @Query(value = """
            select new com.vendorflow.organization.domain.InvitationView(
                i.id, i.email, i.role, i.expiresAt, i.createdAt, u.fullName)
            from Invitation i left join AppUser u on u.id = i.invitedByUserId
            where i.organizationId = :organizationId and i.acceptedAt is null and i.revokedAt is null
              and i.expiresAt > :now
            order by i.createdAt desc, i.id
            """,
            countQuery = """
            select count(i) from Invitation i
            where i.organizationId = :organizationId and i.acceptedAt is null and i.revokedAt is null
              and i.expiresAt > :now
            """)
    Page<InvitationView> findPending(@Param("organizationId") UUID organizationId, @Param("now") Instant now,
            Pageable pageable);
}
