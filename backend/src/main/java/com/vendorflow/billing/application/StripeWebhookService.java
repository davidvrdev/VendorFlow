package com.vendorflow.billing.application;

import com.vendorflow.audit.AuditService;
import com.vendorflow.billing.domain.Subscription;
import com.vendorflow.billing.domain.SubscriptionStatus;
import com.vendorflow.billing.infrastructure.SubscriptionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Processes signature-verified Stripe events (docs/SECURITY.md "Webhook forgery / replay").
 *
 * <ul>
 *   <li><b>Idempotency, insert-first</b>: one statement claims the event id in {@code stripe_event} in the SAME
 *       transaction as the state change. A duplicate delivery finds a PROCESSED row and does nothing; a failed
 *       attempt rolls the claim back (and is recorded as FAILED in a separate transaction) so Stripe's retry runs
 *       again.</li>
 *   <li><b>Tenant resolution</b>: only through the Stripe customer id we stored ourselves (plus, for a completed
 *       checkout whose customer is not stored yet, the client reference id of an existing row without a customer).
 *       Anything else in the payload is ignored.</li>
 *   <li><b>Ordering</b>: the event only says "something changed for subscription X"; the state is re-read from Stripe
 *       through {@link BillingGateway}, so out-of-order or replayed deliveries converge on Stripe's current truth.</li>
 * </ul>
 */
@Service
public class StripeWebhookService {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookService.class);

    private final WebhookVerifier verifier;
    private final BillingGateway gateway;
    private final SubscriptionRepository subscriptions;
    private final BillingProperties properties;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public StripeWebhookService(WebhookVerifier verifier, BillingGateway gateway, SubscriptionRepository subscriptions,
            BillingProperties properties, AuditService audit, JdbcClient jdbc,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.verifier = verifier;
        this.gateway = gateway;
        this.subscriptions = subscriptions;
        this.properties = properties;
        this.audit = audit;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * @throws BillingNotConfiguredException 503 when no webhook secret is configured
     * @throws InvalidWebhookException 400 bad signature / stale timestamp / unparsable body
     * @throws RuntimeException any processing failure (500: Stripe retries the delivery)
     */
    public void handle(String payload, String signatureHeader) {
        if (!properties.webhookConfigured()) {
            throw new BillingNotConfiguredException();
        }
        WebhookEvent event = verifier.verify(payload, signatureHeader);
        try {
            if (alreadyProcessed(event.id())) {
                log.info("Stripe event already processed: eventId={} type={}", event.id(), event.type());
                return;
            }
            // Network call to Stripe BEFORE the transaction: no DB connection/row locks held while we wait on it.
            Optional<RemoteSubscription> remote = fetchRemote(event);
            tx.executeWithoutResult(status -> process(event, remote));
        } catch (RuntimeException e) {
            recordFailure(event, e);
            throw e;
        }
    }

    private boolean alreadyProcessed(String eventId) {
        return jdbc.sql("select count(*) from stripe_event where id = :id and status = 'PROCESSED'")
                .param("id", eventId).query(Integer.class).single() > 0;
    }

    private Optional<RemoteSubscription> fetchRemote(WebhookEvent event) {
        boolean relevant = switch (event.type()) {
            case "checkout.session.completed", "customer.subscription.created", "customer.subscription.updated",
                    "customer.subscription.deleted", "invoice.payment_failed" -> true;
            default -> false;
        };
        if (!relevant || event.customerId() == null || event.subscriptionId() == null) {
            return Optional.empty();
        }
        return gateway.retrieveSubscription(event.subscriptionId());
    }

    private void process(WebhookEvent event, Optional<RemoteSubscription> remote) {
        Instant now = clock.instant();
        int claimed = jdbc.sql("""
                insert into stripe_event (id, type, stripe_created_at, received_at, processed_at, status)
                values (:id, :type, :created, :now, :now, 'PROCESSED')
                on conflict (id) do update
                    set status = 'PROCESSED', processed_at = excluded.processed_at, error = null
                    where stripe_event.status = 'FAILED'""")
                .param("id", event.id())
                .param("type", event.type())
                .param("created", java.sql.Timestamp.from(event.created()))
                .param("now", java.sql.Timestamp.from(now))
                .update();
        if (claimed == 0) {
            log.info("Stripe event already processed: eventId={} type={}", event.id(), event.type());
            return;
        }
        switch (event.type()) {
            case "checkout.session.completed" -> onCheckoutCompleted(event, remote);
            case "customer.subscription.created", "customer.subscription.updated", "customer.subscription.deleted",
                    "invoice.payment_failed" -> onSubscriptionChanged(event, remote);
            default -> log.info("Stripe event ignored: eventId={} type={}", event.id(), event.type());
        }
    }

    private void recordFailure(WebhookEvent event, RuntimeException e) {
        try {
            tx.executeWithoutResult(status -> jdbc.sql("""
                    insert into stripe_event (id, type, stripe_created_at, received_at, status, error)
                    values (:id, :type, :created, :now, 'FAILED', :error)
                    on conflict (id) do update set error = excluded.error, received_at = excluded.received_at
                        where stripe_event.status = 'FAILED'""")
                    .param("id", event.id())
                    .param("type", event.type())
                    .param("created", java.sql.Timestamp.from(event.created()))
                    .param("now", java.sql.Timestamp.from(clock.instant()))
                    .param("error", e.getClass().getSimpleName())
                    .update());
        } catch (RuntimeException secondary) {
            log.error("Could not record failed Stripe event: eventId={}", event.id());
        }
        log.error("Stripe event processing failed: eventId={} type={} error={}", event.id(), event.type(),
                e.getClass().getSimpleName());
    }

    private void onCheckoutCompleted(WebhookEvent event, Optional<RemoteSubscription> remote) {
        if (event.customerId() == null) {
            log.info("Checkout event without customer ignored: eventId={}", event.id());
            return;
        }
        Optional<Subscription> row = subscriptions.findByStripeCustomerIdForUpdate(event.customerId());
        if (row.isPresent() && referencesAnotherOrganization(event, row.get())) {
            // Acknowledge (event is recorded PROCESSED): a retry could never succeed, so do not make Stripe retry.
            log.warn("Checkout customer already bound to another organization, ignored: eventId={}", event.id());
            return;
        }
        if (row.isEmpty()) {
            row = fromClientReference(event);
        }
        if (row.isEmpty()) {
            log.info("Stripe event for unknown customer ignored: eventId={} type={}", event.id(), event.type());
            return;
        }
        sync(row.get(), event, remote, "billing.checkout.completed");
    }

    private static boolean referencesAnotherOrganization(WebhookEvent event, Subscription row) {
        if (event.clientReferenceId() == null) {
            return false;
        }
        try {
            return !UUID.fromString(event.clientReferenceId()).equals(row.getOrganizationId());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Only an existing row that has no customer yet, so a payload can never re-point an organization's customer. */
    private Optional<Subscription> fromClientReference(WebhookEvent event) {
        UUID organizationId;
        try {
            organizationId = event.clientReferenceId() == null ? null : UUID.fromString(event.clientReferenceId());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (organizationId == null) {
            return Optional.empty();
        }
        return subscriptions.findByOrganizationIdForUpdate(organizationId)
                .filter(s -> s.getStripeCustomerId() == null)
                .map(s -> {
                    s.attachCustomer(event.customerId(), clock.instant());
                    return s;
                });
    }

    private void onSubscriptionChanged(WebhookEvent event, Optional<RemoteSubscription> remote) {
        if (event.customerId() == null) {
            return;
        }
        Optional<Subscription> row = subscriptions.findByStripeCustomerIdForUpdate(event.customerId());
        if (row.isEmpty()) {
            log.info("Stripe event for unknown customer ignored: eventId={} type={}", event.id(), event.type());
            return;
        }
        String action = switch (event.type()) {
            case "customer.subscription.created" -> "billing.subscription.created";
            case "customer.subscription.deleted" -> "billing.subscription.deleted";
            case "invoice.payment_failed" -> "billing.payment.failed";
            default -> "billing.subscription.updated";
        };
        sync(row.get(), event, remote, action);
    }

    /** Re-reads the subscription from Stripe and copies its state onto our row (see class comment: ordering). */
    private void sync(Subscription row, WebhookEvent event, Optional<RemoteSubscription> fetched,
            String auditAction) {
        String subscriptionId = event.subscriptionId();
        if (subscriptionId == null) {
            return;
        }
        if (fetched.isEmpty()) {
            log.warn("Stripe subscription not found on re-fetch: eventId={}", event.id());
            return;
        }
        RemoteSubscription remote = fetched.get();
        // Defense in depth: the subscription must belong to the customer we resolved the organization from.
        if (!row.getStripeCustomerId().equals(remote.customerId())) {
            log.warn("Stripe subscription/customer mismatch ignored: eventId={}", event.id());
            return;
        }
        if (remote.status() == null) {
            log.warn("Unknown Stripe subscription status ignored: eventId={}", event.id());
            return;
        }
        // A late event about an OLD, ended subscription must not undo a newer subscription of the same customer.
        if (row.getStripeSubscriptionId() != null && !row.getStripeSubscriptionId().equals(remote.id())
                && remote.status().isTerminal()) {
            log.info("Stripe event for superseded subscription ignored: eventId={}", event.id());
            return;
        }
        SubscriptionStatus before = row.getStatus();
        row.applyStripeState(remote.status(), remote.id(), remote.currentPeriodEnd(), remote.cancelAtPeriodEnd(),
                clock.instant());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("stripeEventId", event.id());
        metadata.put("before", before.name());
        metadata.put("after", remote.status().name());
        audit.record(row.getOrganizationId(), null, auditAction, "subscription", row.getId(), metadata);
    }
}
