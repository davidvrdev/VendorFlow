package com.vendorflow.organization.domain;

import java.time.Instant;
import java.util.UUID;

/** API shape "Invitation". Never contains the token or its hash. */
public record InvitationView(UUID id, String email, Role role, Instant expiresAt, Instant createdAt,
        InvitedBy invitedBy) {

    public record InvitedBy(String fullName) {
    }

    /** JPQL constructor projection: {@code inviterName} is null when the inviting user no longer exists. */
    public InvitationView(UUID id, String email, Role role, Instant expiresAt, Instant createdAt,
            String inviterName) {
        this(id, email, role, expiresAt, createdAt, inviterName == null ? null : new InvitedBy(inviterName));
    }
}
