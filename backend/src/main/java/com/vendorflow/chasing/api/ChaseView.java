package com.vendorflow.chasing.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** API shape "Chase": one automated follow-up. Never contains a token, a link URL or the recipient address. */
public record ChaseView(UUID id, LocalDate date, int attempt, List<TypeRef> types, Instant createdAt, String linkStatus,
        String emailStatus) {

    public record TypeRef(UUID id, String name, String status) {
    }
}
