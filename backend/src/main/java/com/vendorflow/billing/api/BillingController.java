package com.vendorflow.billing.api;

import com.vendorflow.billing.application.BillingSessionService;
import com.vendorflow.billing.application.SubscriptionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Billing of the caller's ACTIVE organization (no organization id anywhere in the request). */
@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {

    private final SubscriptionService subscriptions;
    private final BillingSessionService sessions;

    public BillingController(SubscriptionService subscriptions, BillingSessionService sessions) {
        this.subscriptions = subscriptions;
        this.sessions = sessions;
    }

    @GetMapping("/subscription")
    public SubscriptionView subscription() {
        return subscriptions.current();
    }

    @PostMapping("/checkout-session")
    public SessionUrlView checkoutSession() {
        return sessions.createCheckoutSession();
    }

    @PostMapping("/portal-session")
    public SessionUrlView portalSession() {
        return sessions.createPortalSession();
    }
}
