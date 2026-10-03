package com.vendorflow.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** toString is overridden so the token and the new password can never reach a log line. */
public record PasswordResetConfirmRequest(
        @NotBlank @Size(max = 200) String token,
        @NotNull @Size(min = 12, max = 128) String newPassword) {

    @Override
    public String toString() {
        return "PasswordResetConfirmRequest[redacted]";
    }
}
