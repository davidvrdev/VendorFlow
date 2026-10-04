package com.vendorflow.organization.domain;

import java.util.UUID;

/** A member who should receive operational e-mail (verified OWNER/ADMIN). Read model, not an entity. */
public record StaffRecipient(UUID userId, String email) {
}
