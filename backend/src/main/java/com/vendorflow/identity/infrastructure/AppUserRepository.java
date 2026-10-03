package com.vendorflow.identity.infrastructure;

import com.vendorflow.identity.domain.AppUser;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    /** Matches the unique index on lower(email). */
    @Query("select u from AppUser u where lower(u.email) = lower(:email)")
    Optional<AppUser> findByEmailIgnoreCase(@Param("email") String email);

    /**
     * Atomic increment (no read-modify-write race between parallel guesses). Locks once the NEW count reaches the
     * threshold; later failures while the counter stays at/above it re-lock, so after a lock expires a single
     * wrong guess locks again.
     */
    @Modifying
    @Query("""
            update AppUser u
            set u.failedLoginAttempts = u.failedLoginAttempts + 1,
                u.lockedUntil = case when u.failedLoginAttempts + 1 >= :threshold then :lockUntil else u.lockedUntil end,
                u.updatedAt = :now
            where u.id = :id
            """)
    int recordFailedLogin(@Param("id") UUID id, @Param("threshold") int threshold,
            @Param("lockUntil") Instant lockUntil, @Param("now") Instant now);

    @Modifying
    @Query("""
            update AppUser u
            set u.failedLoginAttempts = 0, u.lockedUntil = null, u.lastLoginAt = :now, u.updatedAt = :now
            where u.id = :id
            """)
    int recordSuccessfulLogin(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying
    @Query("update AppUser u set u.lastActiveOrganizationId = :orgId, u.updatedAt = :now where u.id = :id")
    int updateLastActiveOrganization(@Param("id") UUID id, @Param("orgId") UUID organizationId,
            @Param("now") Instant now);
}
