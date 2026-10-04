package com.vendorflow.chasing.application;

/** Chasing configuration of one organization (ADR-0012). Defaults apply when the organization never saved settings. */
public record ChasingSettings(boolean enabled, int cadenceDays, int maxAttempts, int leadDays, int sendHourLocal,
        boolean ccStaff) {

    /** Opt-in: nothing is ever sent for an organization that did not turn chasing on. */
    public static final ChasingSettings DEFAULTS = new ChasingSettings(false, 7, 4, 30, 9, false);
}
