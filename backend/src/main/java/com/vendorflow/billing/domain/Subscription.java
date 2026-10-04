package com.vendorflow.billing.domain;

import com.vendorflow.shared.persistence.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One per organization. Stripe-derived fields change only through {@link #applyStripeState}. */
@Entity
@Table(name = "subscription")
public class Subscription extends UuidEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubscriptionStatus status;

    @Column(nullable = false)
    private String plan;

    @Column(name = "stripe_customer_id")
    private String stripeCustomerId;

    @Column(name = "stripe_subscription_id")
    private String stripeSubscriptionId;

    @Column(name = "trial_ends_at")
    private Instant trialEndsAt;

    @Column(name = "current_period_end")
    private Instant currentPeriodEnd;

    @Column(name = "cancel_at_period_end", nullable = false)
    private boolean cancelAtPeriodEnd;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Subscription() {
    }

    private Subscription(UUID id) {
        super(id);
    }

    public static Subscription startTrial(UUID organizationId, String plan, Instant now, Instant trialEndsAt) {
        Subscription s = new Subscription(UUID.randomUUID());
        s.organizationId = organizationId;
        s.status = SubscriptionStatus.TRIALING;
        s.plan = plan;
        s.trialEndsAt = trialEndsAt;
        s.createdAt = now;
        s.updatedAt = now;
        return s;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public SubscriptionStatus getStatus() {
        return status;
    }

    public String getPlan() {
        return plan;
    }

    public String getStripeCustomerId() {
        return stripeCustomerId;
    }

    public String getStripeSubscriptionId() {
        return stripeSubscriptionId;
    }

    public Instant getTrialEndsAt() {
        return trialEndsAt;
    }

    public Instant getCurrentPeriodEnd() {
        return currentPeriodEnd;
    }

    public boolean isCancelAtPeriodEnd() {
        return cancelAtPeriodEnd;
    }

    public void attachCustomer(String customerId, Instant now) {
        this.stripeCustomerId = customerId;
        this.updatedAt = now;
    }

    public void attachSubscription(String subscriptionId, Instant now) {
        this.stripeSubscriptionId = subscriptionId;
        this.updatedAt = now;
    }

    /** Copies the state of the (re-fetched) Stripe subscription. The trial date is kept: it is ours, not Stripe's. */
    public void applyStripeState(SubscriptionStatus newStatus, String subscriptionId, Instant periodEnd,
            boolean cancelAtPeriodEnd, Instant now) {
        this.status = newStatus;
        this.stripeSubscriptionId = subscriptionId;
        this.currentPeriodEnd = periodEnd;
        this.cancelAtPeriodEnd = cancelAtPeriodEnd;
        this.updatedAt = now;
    }
}
