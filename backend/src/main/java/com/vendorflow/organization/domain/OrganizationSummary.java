package com.vendorflow.organization.domain;

import java.util.UUID;

/** An organization as seen by one of its members (also the JPQL constructor projection). */
public record OrganizationSummary(UUID id, String name, Role role) {
}
