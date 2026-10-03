package com.vendorflow.organization.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** toString hides the token from logs. */
public record InvitationTokenRequest(@NotBlank @Size(max = 200) String token) {

    @Override
    public String toString() {
        return "InvitationTokenRequest[redacted]";
    }
}
