package com.vendorflow.organization.api;

import com.vendorflow.organization.domain.Role;
import jakarta.validation.constraints.NotNull;

public record ChangeRoleRequest(@NotNull Role role) {
}
