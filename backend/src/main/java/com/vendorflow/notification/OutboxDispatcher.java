package com.vendorflow.notification;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
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
 * since provider errors may echo request data). Backoff 1m, 5m, 30m, 2h, 6h; the 6th failed attempt marks the row
 * DEAD. Secrets (the raw token) are removed from the payload when a row reaches SENT or DEAD.
 */
@Service
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final int MAX_ERROR_LENGTH = 500;
    /** Long url-safe/base64 runs (token-like) and explicit token=... fragments. */
    private static final Pattern TOKEN_LIKE = Pattern.compile("(?i)(token=)?[A-Za-z0-9_-]{32,}");

    private final NotificationRepository notifications;
    private final EmailSender emailSender;
    private final EmailTemplates templates;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int batchSize;

    public OutboxDispatcher(NotificationRepository notifications, EmailSender emailSender, EmailTemplates templates,
            PlatformTransactionManager transactionManager, Clock clock,
            @Value("${app.outbox.dispatcher.batch-size:20}") int batchSize) {
        this.notifications = notifications;
        this.emailSender = emailSender;
        this.templates = templates;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.batchSize = batchSize;
    }

    /** @return number of notifications processed (sent or failed) in this batch */
    public int dispatchBatch() {
        Integer processed = tx.execute(status -> {
            Instant now = clock.instant();
            List<Notification> due = notifications.claimDue(now, batchSize);
            for (Notification n : due) {
                process(n, now);
            }
            return due.size();
        });
        return processed == null ? 0 : processed;
    }

    private void process(Notification n, Instant now) {
        try {
            RenderedEmail email = templates.render(n.getKind(), n.getPayload());
            String providerId = emailSender.send(new EmailMessage(n.getId(), n.getIdempotencyKey(),
                    n.getRecipientEmail(), email.subject(), email.textBody(), email.htmlBody(), n.getKind()));
            n.markSent(providerId, now);
            log.info("Notification sent: id={} kind={}", n.getId(), n.getKind());
        } catch (RuntimeException e) {
            n.markFailed(sanitize(e), now);
            log.warn("Notification attempt failed: id={} kind={} attempts={} status={}", n.getId(), n.getKind(),
                    n.getAttempts(), n.getStatus());
        }
    }

    static String sanitize(Exception e) {
        String message = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        String cleaned = TOKEN_LIKE.matcher(message).replaceAll("[redacted]").replaceAll("[\\r\\n]+", " ");
        return cleaned.length() <= MAX_ERROR_LENGTH ? cleaned : cleaned.substring(0, MAX_ERROR_LENGTH);
    }
}
