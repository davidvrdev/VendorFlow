package com.vendorflow.billing.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code vendorflow.billing.*}. Secrets come from the environment only (STRIPE_SECRET_KEY, STRIPE_WEBHOOK_SECRET,
 * STRIPE_PRICE_ID); nothing here is ever logged or serialized.
 */
@Component
@ConfigurationProperties(prefix = "vendorflow.billing")
public class BillingProperties {

    /** Master switch. Off: no read-only enforcement at all and no Stripe calls (local development default). */
    private boolean enabled = false;
    private int trialDays = 14;
    /** Plan key stored on the subscription; one plan for now (pricing/limits are an open owner decision). */
    private String planKey = "standard";
    /** Max accepted age of a webhook signature timestamp (replay window). */
    private long webhookToleranceSeconds = 300;
    /** Cap on the webhook request body; real Stripe events are a few KB. */
    private int webhookMaxBodyBytes = 262_144;
    private final Stripe stripe = new Stripe();

    public static class Stripe {
        private String secretKey = "";
        private String webhookSecret = "";
        private String priceId = "";

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getWebhookSecret() {
            return webhookSecret;
        }

        public void setWebhookSecret(String webhookSecret) {
            this.webhookSecret = webhookSecret;
        }

        public String getPriceId() {
            return priceId;
        }

        public void setPriceId(String priceId) {
            this.priceId = priceId;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getTrialDays() {
        return trialDays;
    }

    public void setTrialDays(int trialDays) {
        this.trialDays = trialDays;
    }

    public String getPlanKey() {
        return planKey;
    }

    public void setPlanKey(String planKey) {
        this.planKey = planKey;
    }

    public long getWebhookToleranceSeconds() {
        return webhookToleranceSeconds;
    }

    public void setWebhookToleranceSeconds(long webhookToleranceSeconds) {
        this.webhookToleranceSeconds = webhookToleranceSeconds;
    }

    public int getWebhookMaxBodyBytes() {
        return webhookMaxBodyBytes;
    }

    public void setWebhookMaxBodyBytes(int webhookMaxBodyBytes) {
        this.webhookMaxBodyBytes = webhookMaxBodyBytes;
    }

    public Stripe getStripe() {
        return stripe;
    }

    /** Checkout/portal need the API key and the price; false means those endpoints answer 503. */
    public boolean apiConfigured() {
        return enabled && has(stripe.secretKey) && has(stripe.priceId);
    }

    /** The webhook endpoint needs the signing secret. */
    public boolean webhookConfigured() {
        return enabled && has(stripe.webhookSecret);
    }

    private static boolean has(String value) {
        return value != null && !value.isBlank();
    }
}
