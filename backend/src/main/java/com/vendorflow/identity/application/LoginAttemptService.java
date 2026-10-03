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

    @Transactional
    public void recordFailure(UUID userId) {
        Instant now = clock.instant();
        users.recordFailedLogin(userId, MAX_FAILURES, now.plus(LOCK_DURATION), now);
    }

    @Transactional
    public void recordSuccess(UUID userId) {
        users.recordSuccessfulLogin(userId, clock.instant());
    }
}
