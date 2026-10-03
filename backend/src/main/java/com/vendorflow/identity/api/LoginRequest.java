package com.vendorflow.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(max = 254) String email,
        @NotBlank @Size(max = 128) String password) {

    @Override
    public String toString() {
        return "LoginRequest[redacted]";
    }
}
