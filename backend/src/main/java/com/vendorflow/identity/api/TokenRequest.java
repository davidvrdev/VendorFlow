package com.vendorflow.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of verify-email: the raw token from the emailed link. toString hides it from logs. */
public record TokenRequest(@NotBlank @Size(max = 200) String token) {

    @Override
    public String toString() {
        return "TokenRequest[redacted]";
    }
}
