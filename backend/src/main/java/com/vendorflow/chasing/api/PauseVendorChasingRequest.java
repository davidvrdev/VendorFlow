package com.vendorflow.chasing.api;

import jakarta.validation.constraints.NotNull;

/** Body of {@code PUT /vendors/{id}/chasing}. */
public record PauseVendorChasingRequest(@NotNull Boolean paused) {
}
