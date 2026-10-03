package com.vendorflow.identity.api;

import com.vendorflow.shared.validation.PlainText;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** toString is overridden so the password can never reach a log line through this record. */
public record SignupRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotNull @Size(min = 12, max = 128) String password,
        @NotBlank @Size(max = 100) @PlainText String fullName,
        @NotBlank @Size(max = 120) @PlainText String organizationName) {

    @Override
    public String toString() {
        return "SignupRequest[redacted]";
    }
}
