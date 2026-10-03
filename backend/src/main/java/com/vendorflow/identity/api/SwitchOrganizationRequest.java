package com.vendorflow.identity.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** The one place a client names an organization; the server verifies membership before honoring it. */
public record SwitchOrganizationRequest(@NotNull UUID organizationId) {
}
