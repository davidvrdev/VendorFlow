package com.vendorflow.vendor.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record DocumentRequestRequest(@NotNull UUID documentTypeId) {
}
