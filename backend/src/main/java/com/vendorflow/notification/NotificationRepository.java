package com.vendorflow.notification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
