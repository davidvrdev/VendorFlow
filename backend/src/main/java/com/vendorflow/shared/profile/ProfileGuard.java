package com.vendorflow.shared.profile;

import java.util.Arrays;
import java.util.Collection;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when a test-only profile is active together with production. Profile {@code e2e} swaps in an
 * in-memory mailbox that exposes emailed secret links over HTTP without authentication; running that in prod would
 * be an account-takeover hole, so it is a startup failure rather than a convention.
 */
@Component
public class ProfileGuard {

    public ProfileGuard(Environment environment) {
        check(Arrays.asList(environment.getActiveProfiles()));
    }

    /** @throws IllegalStateException if both {@code e2e} and {@code prod} are active */
    public static void check(Collection<String> activeProfiles) {
        if (activeProfiles.contains("e2e") && activeProfiles.contains("prod")) {
            throw new IllegalStateException(
                    "Profiles 'e2e' and 'prod' must never be active together (e2e exposes a public mailbox).");
        }
    }
}
