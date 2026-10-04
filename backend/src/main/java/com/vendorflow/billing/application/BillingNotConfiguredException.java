package com.vendorflow.billing.application;

import com.vendorflow.shared.error.ApiException;
import org.springframework.http.HttpStatus;

/** 503 ProblemDetail "Billing not configured": keys/price missing or billing disabled. Never a fake URL. */
public class BillingNotConfiguredException extends ApiException {

    public BillingNotConfiguredException() {
        super(HttpStatus.SERVICE_UNAVAILABLE, "billing-not-configured", "Billing not configured",
                "Billing is not configured on this server.");
    }
}
