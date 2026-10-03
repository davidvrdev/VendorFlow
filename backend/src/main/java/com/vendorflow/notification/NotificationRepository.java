package com.vendorflow.notification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * Claims due rows. FOR UPDATE SKIP LOCKED makes concurrent dispatchers (several instances, or two
     * overlapping runs) pick disjoint rows instead of blocking or double-sending.
     */
    @Query(value = """
            SELECT * FROM notification
            WHERE status IN ('PENDING', 'FAILED') AND next_attempt_at <= :now
            ORDER BY next_attempt_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Notification> claimDue(@Param("now") Instant now, @Param("limit") int limit);

    Optional<Notification> findByIdempotencyKey(String idempotencyKey);

    /**
     * L1: a row that was never delivered must not keep a live-looking secret forever. Rows still PENDING/FAILED after
     * the token's maximum lifetime become DEAD and lose the raw token (the link would be expired anyway).
     * Account emails: 24h; invitations: 7 days (their token lifetime).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE notification
            SET status = 'DEAD', payload = payload - 'token', updated_at = :now,
                last_error = 'Token expired before delivery'
            WHERE status IN ('PENDING', 'FAILED') AND payload -> 'token' IS NOT NULL
              AND ((kind = 'INVITATION' AND created_at < :invitationCutoff)
                   OR (kind <> 'INVITATION' AND created_at < :accountCutoff))
            """, nativeQuery = true)
    int scrubUndeliveredTokens(@Param("now") Instant now, @Param("accountCutoff") Instant accountCutoff,
            @Param("invitationCutoff") Instant invitationCutoff);
}
