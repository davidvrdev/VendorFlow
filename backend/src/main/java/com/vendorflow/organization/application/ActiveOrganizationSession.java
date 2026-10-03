package com.vendorflow.organization.application;

import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import java.util.UUID;

/**
 * The only code that reads/writes the "active organization" session attribute. The value is a hint: the membership
 * behind it is re-verified from the database on every request (TenantContextFilter), so a stale or tampered
 * value can never grant access.
 */
public final class ActiveOrganizationSession {

    static final String ATTRIBUTE = "vf.activeOrganizationId";

    private ActiveOrganizationSession() {
    }

    public static Optional<UUID> get(HttpSession session) {
        if (session != null && session.getAttribute(ATTRIBUTE) instanceof String value) {
            try {
                return Optional.of(UUID.fromString(value));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    public static void set(HttpSession session, UUID organizationId) {
        session.setAttribute(ATTRIBUTE, organizationId.toString());
    }

    public static void clear(HttpSession session) {
        session.removeAttribute(ATTRIBUTE);
    }
}
