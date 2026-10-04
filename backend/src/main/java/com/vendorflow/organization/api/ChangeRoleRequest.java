package com.vendorflow.organization.api;

import com.vendorflow.shared.tenant.Role;
import jakarta.validation.constraints.NotNull;

public record ChangeRoleRequest(@NotNull Role role) {
}
