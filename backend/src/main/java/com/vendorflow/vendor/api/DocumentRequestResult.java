package com.vendorflow.vendor.api;

import java.time.Instant;

public record DocumentRequestResult(Instant requestedAt, String recipientEmail) {
}
