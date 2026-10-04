package com.vendorflow.chasing.api;

/** Response of the public opt-out endpoints: just enough for the unsubscribe page to say who and what. */
public record OptOutView(String organizationName, String vendorName, boolean optedOut) {
}
