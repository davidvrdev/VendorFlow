package com.vendorflow.shared.error;

import org.springframework.http.HttpStatus;

/** Authenticated user calls a tenant endpoint without a (still valid) active organization. */
public class NoActiveOrganizationException extends ApiException {

    public NoActiveOrganizationException() {
        super(HttpStatus.FORBIDDEN, "no-active-organization", "No active organization",
                "Select an organization to continue.");
    }
}
