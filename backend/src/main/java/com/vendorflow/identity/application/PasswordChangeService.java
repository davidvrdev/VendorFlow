package com.vendorflow.identity.application;

import com.vendorflow.audit.AuditService;
import com.vendorflow.identity.domain.AppUser;
import com.vendorflow.identity.infrastructure.AppUserRepository;
import com.vendorflow.notification.NotificationKind;
import com.vendorflow.notification.OutboxService;
import com.vendorflow.shared.error.ApiException;
import com.vendorflow.shared.error.FieldViolation;
import com.vendorflow.shared.error.RequestValidationException;
import com.vendorflow.shared.ratelimit.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Change of the signed-in user's own password (ASVS V2.1.5/2.1.6/3.7.1). Authorization: any authenticated user may
 * change THEIR OWN password; the user id comes from the session, never from the request, and no organization is
 * involved, so there is no tenant data to cross.
 *
 * <p>Deliberately not @Transactional as a whole, like AuthService: a wrong current password must still count toward
 * the account lockout (LoginAttemptService commits on its own) although the request ends in an error.
 */
@Service
public class PasswordChangeService {

    private static final Logger log = LoggerFactory.getLogger(PasswordChangeService.class);
    static final String RATE_RULE = "password-change";

    private final AppUserRepository users;
    private final PasswordVerifier verifier;
    private final PasswordPolicy policy;
    private final LoginAttemptService loginAttempts;
    private final SessionService sessions;
    private final AuditService audit;
    private final OutboxService outbox;
    private final RateLimiter rateLimiter;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PasswordChangeService(AppUserRepository users, PasswordVerifier verifier, PasswordPolicy policy,
            LoginAttemptService loginAttempts, SessionService sessions, AuditService audit, OutboxService outbox,
            RateLimiter rateLimiter, PlatformTransactionManager transactionManager, Clock clock) {
        this.users = users;
        this.verifier = verifier;
        this.policy = policy;
        this.loginAttempts = loginAttempts;
        this.sessions = sessions;
        this.audit = audit;
        this.outbox = outbox;
        this.rateLimiter = rateLimiter;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public void change(UUID userId, String currentPassword, String newPassword, HttpServletRequest request,
            HttpServletResponse response) {
        // Per user, not per IP: the endpoint is an oracle for the current password, so guessing is capped per account.
        RateLimiter.Decision decision = rateLimiter.tryAcquire(RATE_RULE, userId.toString());
        if (!decision.allowed()) {
            log.warn("Rate limit exceeded: rule={}", RATE_RULE);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "Too many requests",
                    "Too many requests. Please try again later.", decision.retryAfterSeconds());
        }
        AppUser user = users.findById(userId).orElseThrow(() -> new BadCredentialsException("Unknown user"));
        if (!verifier.matches(currentPassword, user.getPasswordHash())) {
            loginAttempts.recordFailure(userId);
            log.warn("Password change refused: wrong current password userId={}", userId);
            throw wrongCurrentPassword();
        }
        if (!loginAttempts.recordSuccess(userId)) {
            // Locked account: same answer as a wrong password, even when the password was right.
            log.warn("Password change refused: account locked userId={}", userId);
            throw wrongCurrentPassword();
        }
        policy.validate(newPassword, user.getEmail(), "newPassword");
        if (newPassword.equals(currentPassword)) {
            throw new RequestValidationException(List.of(
                    new FieldViolation("newPassword", "must be different from the current password")));
        }
        String newHash = verifier.encode(newPassword);
        String currentSessionId = request.getSession(true).getId();
        // One transaction: new hash, revocation of every OTHER session (Spring Session JDBC joins it), audit row and
        // the notice email commit together or not at all.
        tx.executeWithoutResult(s -> {
            AppUser fresh = users.findById(userId).orElseThrow(() -> new BadCredentialsException("Unknown user"));
            fresh.changePassword(newHash, clock.instant());
            sessions.endAllExcept(userId, currentSessionId);
            audit.record(null, userId, "user.password_changed", "user", userId, Map.of());
            outbox.enqueue(NotificationKind.PASSWORD_CHANGED, null, fresh.getEmail(),
                    "password-changed:" + UUID.randomUUID(), Map.of("fullName", fresh.getFullName()));
        });
        // Privilege change: the surviving session gets a new id and CSRF token.
        sessions.rotate(request, response);
        log.info("Password changed: userId={}", userId);
    }

    private static ApiException wrongCurrentPassword() {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid-current-password", "Password change failed",
                "The current password is incorrect.");
    }
}
