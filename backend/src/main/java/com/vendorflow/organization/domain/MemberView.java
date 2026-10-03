package com.vendorflow.organization.domain;

import java.time.Instant;
import java.util.UUID;

/** API shape "Member" (also the JPQL constructor projection of membership joined with the user). */
public record MemberView(UUID membershipId, UUID userId, String fullName, String email, Role role, Instant joinedAt) {
}
