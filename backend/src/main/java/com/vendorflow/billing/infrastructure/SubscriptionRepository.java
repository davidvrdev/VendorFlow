package com.vendorflow.billing.infrastructure;

import com.vendorflow.billing.domain.Subscription;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    Optional<Subscription> findByOrganizationId(UUID organizationId);

    /**
     * Webhook entry: the Stripe customer id is the ONLY key used to find the organization (we stored it ourselves
     * when the customer was created). Locked so concurrent events for one organization apply one after the other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Subscription s where s.stripeCustomerId = :customerId")
    Optional<Subscription> findByStripeCustomerIdForUpdate(@Param("customerId") String customerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Subscription s where s.organizationId = :organizationId")
    Optional<Subscription> findByOrganizationIdForUpdate(@Param("organizationId") UUID organizationId);
}
