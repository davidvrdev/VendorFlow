package com.vendorflow.billing.application;

/** Provider call failed. The message must never carry keys or payloads (callers log only the class name). */
public class BillingGatewayException extends RuntimeException {

    public BillingGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
