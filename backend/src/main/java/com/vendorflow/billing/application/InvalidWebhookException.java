package com.vendorflow.billing.application;

import com.vendorflow.shared.error.ApiException;
import org.springframework.http.HttpStatus;

/** Webhook rejected before any processing (400). Generic on purpose: never hints why, never echoes the payload. */
public class InvalidWebhookException extends ApiException {

    public InvalidWebhookException() {
        super(HttpStatus.BAD_REQUEST, "invalid-webhook", "Invalid webhook", "The webhook could not be verified.");
    }
}
