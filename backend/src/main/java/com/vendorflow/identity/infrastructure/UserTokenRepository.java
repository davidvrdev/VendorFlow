package com.vendorflow.identity.infrastructure;

import com.vendorflow.identity.domain.UserToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserTokenRepository extends JpaRepository<UserToken, UUID> {

    Optional<UserToken> findByTokenHash(String tokenHash);

    /** Atomic single-use claim: 1 if this call consumed the token, 0 if it was already used (concurrent request). */
    @Modifying
    @Query("update UserToken t set t.usedAt = :now where t.id = :id and t.usedAt is null")
    int markUsed(@Param("id") UUID id, @Param("now") Instant now);

    /** Retires every outstanding link of a purpose: a newly issued link replaces the older ones. */
    @Modifying
    @Query("""
            update UserToken t set t.usedAt = :now
            where t.userId = :userId and t.purpose = :purpose and t.usedAt is null
            """)
    int invalidateUnused(@Param("userId") UUID userId, @Param("purpose") UserToken.Purpose purpose,
            @Param("now") Instant now);
}
