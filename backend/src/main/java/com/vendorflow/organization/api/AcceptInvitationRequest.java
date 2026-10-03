package com.vendorflow.organization.api;

import com.vendorflow.shared.validation.PlainText;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** fullName/password are only needed when the invited person has no account yet and is not signed in. */
public record AcceptInvitationRequest(
        @NotBlank @Size(max = 200) String token,
        @Size(max = 100) @PlainText String fullName,
        @Size(max = 128) String password) {

    @Override
    public String toString() {
        return "AcceptInvitationRequest[redacted]";
    }
}
