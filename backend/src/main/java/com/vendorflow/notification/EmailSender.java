package com.vendorflow.notification;

/**
 * Boundary to the email provider (Resend in Phase 6). Implementations must pass {@link EmailMessage#idempotencyKey()}
 * to the provider so a retry after a crash does not produce a duplicate email.
 */
public interface EmailSender {

    /**
     * @return the provider's message id (may be a synthetic id for non-delivering senders)
     * @throws RuntimeException on any failure; the dispatcher records it and retries with backoff
     */
    String send(EmailMessage message);
}
