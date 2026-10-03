package com.vendorflow.identity.application;

import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** The authenticated user's id (the session principal name is the user id string). */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** @throws org.springframework.security.core.AuthenticationException (401) if nobody valid is logged in */
    public static UUID id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new BadCredentialsException("Not authenticated");
        }
        try {
            return UUID.fromString(auth.getName());
        } catch (IllegalArgumentException e) {
            throw new BadCredentialsException("Not authenticated");
        }
    }
}
