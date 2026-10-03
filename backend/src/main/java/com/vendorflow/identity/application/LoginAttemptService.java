package com.vendorflow.identity.application;

import com.vendorflow.identity.infrastructure.AppUserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-account brute-force lockout: 5 consecutive failures lock the account for 15 minutes; a success resets the
 * counter. Each method is its own short transaction on purpose: the counter must persist even though the login
 * request itself ends in an error response. Updates are atomic SQL, safe for concurrent guesses and instances.
 */
@Service
public class LoginAttemptService {

    public static final int MAX_FAILURES = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final AppUserRepository users;
    private final Clock clock;

    public LoginAttemptService(AppUserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    /** @return false if the account was already locked (the failure is then not counted; the lock has a fixed length) */
    @Transactional
    public boolean recordFailure(UUID userId) {
        Instant now = clock.instant();
        return users.recordFailedLogin(userId, MAX_FAILURES, now.plus(LOCK_DURATION), now) == 1;
    }

    /** @return false if the account is locked at this moment: the login must be rejected even with a correct password */
    @Transactional
    public boolean recordSuccess(UUID userId) {
        return users.recordSuccessfulLogin(userId, clock.instant()) == 1;
    }
}
