package com.vendorflow.organization.domain;

import java.util.UUID;

/**
 * Published (synchronously, inside the signup transaction) when an organization is created. Other features react to it
 * (e.g. document seeds the default document types) so that organization does not depend on them. Listeners run in the
 * publisher's transaction: if one fails, the whole signup rolls back.
 */
public record OrganizationCreatedEvent(UUID organizationId) {
}
