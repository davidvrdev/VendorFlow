package com.vendorflow.notification;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Consumer side of the outbox (ADR-0006).
 *
 * <p><b>Transaction design.</b> One database transaction per batch: it claims due rows with
 * {@code FOR UPDATE SKIP LOCKED}, sends each one, updates its state, and commits. The row locks are therefore held
 * while we call the email provider. That is acceptable at our scale (a small batch, a provider call is ~100ms, and
 * other instances simply skip locked rows) and it buys simplicity: there is no "SENDING" state that a crashed
 * instance could leave stuck. The price is that a crash AFTER the provider accepted a message but BEFORE commit
 * rolls the row back and it is retried; to make that harmless the notification's idempotency key is passed to the
 * sender so the provider can deduplicate (Resend supports an Idempotency-Key header).
 *
 * <p>A failing send never aborts the batch: the exception is caught per row and recorded (sanitized and truncated,
 * since provider errors may echo request data, including recipient addresses). Backoff 1m, 5m, 30m, 2h, 6h; the 6th failed attempt marks the row
 * DEAD. Secrets (the raw token) are removed from the payload when a row reaches SENT or DEAD.
 */
@Service
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final int MAX_ERROR_LENGTH = 500;
    /** Long url-safe/base64 runs (token-like) and explicit token=... fragments. */
    /** Max lifetime of the raw token an undelivered row may carry (see NotificationRepository#scrubUndeliveredTokens). */
    static final Duration ACCOUNT_TOKEN_MAX_AGE = Duration.ofHours(24);
    static final Duration INVITATION_TOKEN_MAX_AGE = Duration.ofDays(7);
    private static final Pattern EMAIL_ADDRESS = Pattern.compile("\\S+@\\S+");
    private static final Pattern TOKEN_LIKE = Pattern.compile("(?i)(token=)?[A-Za-z0-9_-]{32,}");

    private final NotificationRepository notifications;
    private final EmailSender emailSender;
    private final EmailTemplates templates;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int batchSize;
    private final EmailSuppressionService suppression;
    private final List<DeliveryGuard> guards;

    /** Without suppression list and guards (unit-style tests that build the dispatcher by hand). */
    public OutboxDispatcher(NotificationRepository notifications, EmailSender emailSender, EmailTemplates templates,
            PlatformTransactionManager transactionManager, Clock clock, int batchSize) {
        this(notifications, emailSender, templates, transactionManager, clock, batchSize, null, null);
    }

    @Autowired
    public OutboxDispatcher(NotificationRepository notifications, EmailSender emailSender, EmailTemplates templates,
            PlatformTransactionManager transactionManager, Clock clock,
            @Value("${app.outbox.dispatcher.batch-size:20}") int batchSize, EmailSuppressionService suppression,
            ObjectProvider<DeliveryGuard> guards) {
        this.notifications = notifications;
        this.emailSender = emailSender;
        this.templates = templates;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.batchSize = batchSize;
        this.suppression = suppression;
        this.guards = guards == null ? List.of() : guards.orderedStream().toList();
    }

    /** @return number of notifications processed (sent or failed) in this batch */
    public int dispatchBatch() {
        Integer processed = tx.execute(status -> {
            Instant now = clock.instant();
            int scrubbed = notifications.scrubUndeliveredTokens(now, now.minus(ACCOUNT_TOKEN_MAX_AGE),
                    now.minus(INVITATION_TOKEN_MAX_AGE));
            if (scrubbed > 0) {
                log.info("Outbox: {} undelivered notification(s) past their token lifetime marked DEAD", scrubbed);
            }
            List<Notification> due = notifications.claimDue(now, batchSize);
            for (Notification n : due) {
                process(n, now);
            }
            return due.size();
        });
        return processed == null ? 0 : processed;
    }

    private void process(Notification n, Instant now) {
        String blocked = blockReason(n);
        if (blocked != null) {
            // Backstop: the vendor paused/unsubscribed after the row was enqueued. Never sent; secrets are scrubbed.
            n.markDead(blocked, now);
            log.info("Notification dropped before sending: id={} kind={} reason={}", n.getId(), n.getKind(), blocked);
            return;
        }
        try {
            RenderedEmail email = templates.render(n.getKind(), n.getPayload());
            String providerId = emailSender.send(new EmailMessage(n.getId(), n.getIdempotencyKey(),
                    n.getRecipientEmail(), email.subject(), email.textBody(), email.htmlBody(), n.getKind(), email.replyTo(), email.headers()));
            n.markSent(providerId, now);
            log.info("Notification sent: id={} kind={}", n.getId(), n.getKind());
        } catch (EmailDeliveryException e) {
            if (e.isPermanent()) {
                // The provider rejected the request itself: retrying cannot help, so DEAD at once (no backoff).
                n.markDead(sanitize(e), now);
                log.warn("Notification permanently failed: id={} kind={} status=DEAD", n.getId(), n.getKind());
            } else {
                n.markFailed(sanitize(e), now);
                log.warn("Notification attempt failed: id={} kind={} attempts={} status={}", n.getId(), n.getKind(),
                        n.getAttempts(), n.getStatus());
            }
        } catch (RuntimeException e) {
            n.markFailed(sanitize(e), now);
            log.warn("Notification attempt failed: id={} kind={} attempts={} status={}", n.getId(), n.getKind(),
                    n.getAttempts(), n.getStatus());
        }
    }

    /** Marketing-adjacent kinds honour the global suppression list; features may veto their own kinds. */
    private String blockReason(Notification n) {
        if (suppression != null
                && (n.getKind() == NotificationKind.VENDOR_CHASE || n.getKind() == NotificationKind.DOCUMENT_REQUEST)
                && suppression.isSuppressed(n.getRecipientEmail())) {
            return "Address unsubscribed";
        }
        for (DeliveryGuard guard : guards) {
            String reason = guard.blockReason(n.getKind(), n.getOrganizationId(), n.getPayload());
            if (reason != null) {
                return reason;
            }
        }
        return null;
    }

    static String sanitize(Exception e) {
        String message = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        // Emails first: an address can itself contain a long token-like run.
        String cleaned = TOKEN_LIKE.matcher(EMAIL_ADDRESS.matcher(message).replaceAll("[redacted-email]"))
                .replaceAll("[redacted]").replaceAll("[\\r\\n]+", " ");
        return cleaned.length() <= MAX_ERROR_LENGTH ? cleaned : cleaned.substring(0, MAX_ERROR_LENGTH);
    }
}
