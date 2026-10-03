package com.vendorflow.identity.application;

import com.vendorflow.shared.error.ApiException;
import org.springframework.http.HttpStatus;

/** One body for every way an emailed link can be bad (unknown, expired, used, wrong purpose): no oracle. */
final class InvalidLinkException extends ApiException {

    InvalidLinkException() {
        super(HttpStatus.BAD_REQUEST, "invalid-link", "Invalid or expired link",
                "This link is invalid or has expired. Request a new one.");
    }
}
