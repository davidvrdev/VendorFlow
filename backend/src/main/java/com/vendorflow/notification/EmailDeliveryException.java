package com.vendorflow.notification;

/**
 * Thrown by an EmailSender when delivery failed. {@code permanent} = the provider rejected the request itself
 * (retrying the same request cannot succeed): the dispatcher marks the notification DEAD immediately. Otherwise
 * (rate limit, 5xx, network) the existing backoff applies. The message must never contain bodies, links, API keys or
 * addresses (the dispatcher sanitizes it again before storing).
 */
public class EmailDeliveryException extends RuntimeException {

    private final boolean permanent;

    public EmailDeliveryException(String message, boolean permanent) {
        super(message);
        this.permanent = permanent;
    }

    public EmailDeliveryException(String message, boolean permanent, Throwable cause) {
        super(message, cause);
        this.permanent = permanent;
    }

    public boolean isPermanent() {
        return permanent;
    }
}
