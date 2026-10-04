package com.vendorflow.organization.api;

import com.vendorflow.shared.tenant.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateInvitationRequest(@NotBlank @Email @Size(max = 254) String email, @NotNull Role role) {
}
