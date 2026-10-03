package com.vendorflow.identity.application;

import com.vendorflow.audit.AuditService;
import com.vendorflow.identity.api.LoginRequest;
import com.vendorflow.identity.api.Me;
import com.vendorflow.identity.api.SignupRequest;
import com.vendorflow.identity.domain.AppUser;
import com.vendorflow.identity.infrastructure.AppUserRepository;
import com.vendorflow.organization.application.ActiveOrganizationSession;
import com.vendorflow.organization.application.OrganizationService;
import com.vendorflow.organization.application.TenantContext;
import com.vendorflow.organization.domain.OrganizationSummary;
import com.vendorflow.shared.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Orchestrates login, signup, logout and organization switching. Deliberately NOT @Transactional as a whole: the
 * failed-login counter must be committed even when login ends in an error, and the session is created only after
 * the database work has committed.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptService loginAttempts;
    private final AccountRegistrationService registration;
    private final SessionService sessions;
    private final OrganizationService organizations;
    private final MeService meService;
    private final AuditService audit;
    private final TenantContext tenantContext;
    private final TransactionTemplate tx;
    private final Clock clock;
    /** Verified against when the email is unknown, so "no such user" costs the same bcrypt time as "wrong password". */
    private final String dummyHash;

    public AuthService(AppUserRepository users, PasswordEncoder passwordEncoder, LoginAttemptService loginAttempts,
            AccountRegistrationService registration, SessionService sessions, OrganizationService organizations,
            MeService meService, AuditService audit, TenantContext tenantContext,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.loginAttempts = loginAttempts;
        this.registration = registration;
        this.sessions = sessions;
        this.organizations = organizations;
        this.meService = meService;
        this.audit = audit;
        this.tenantContext = tenantContext;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("timing-equalization-dummy-password");
    }

    public Me login(LoginRequest request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        Optional<AppUser> found = users.findByEmailIgnoreCase(request.email().trim());
        // Always run exactly one bcrypt verification, whatever the outcome (timing equalization).
        boolean fits = fitsBcrypt(request.password());
        boolean passwordMatches = passwordEncoder.matches(fits ? request.password() : "x",
                found.map(AppUser::getPasswordHash).orElse(dummyHash)) && fits;

        if (found.isEmpty()) {
            log.warn("Login failed: unknown account");
            throw invalidCredentials();
        }
        AppUser user = found.get();
        // The lock state read above is a stale snapshot (bcrypt takes ~250ms; parallel guesses all see "unlocked").
        // So the decision is made by the database, atomically, AFTER bcrypt: both updates below only touch an
        // account that is not locked right now.
        if (!passwordMatches) {
            if (loginAttempts.recordFailure(user.getId())) {
                log.warn("Login failed: bad password userId={}", user.getId());
            } else {
                log.warn("Login rejected: account locked userId={}", user.getId());
            }
            throw invalidCredentials();
        }
        if (!loginAttempts.recordSuccess(user.getId())) {
            // Locked: reject even a correct password (including one that was in flight when the lock engaged).
            log.warn("Login rejected: account locked userId={}", user.getId());
            throw invalidCredentials();
        }
        UUID activeOrganizationId = organizations
                .resolveActiveOrganization(user.getId(), user.getLastActiveOrganizationId()).orElse(null);
        sessions.start(user.getId(), activeOrganizationId, httpRequest, httpResponse);
        log.info("Login succeeded: userId={}", user.getId());
        return meService.build(user.getId(), activeOrganizationId);
    }

    public Me signup(SignupRequest request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        AccountRegistrationService.Registered registered = registration.register(request);
        // Same session handling as login, including fixation protection and CSRF rotation.
        sessions.start(registered.userId(), registered.organizationId(), httpRequest, httpResponse);
        log.info("Signup succeeded: userId={}", registered.userId());
        return meService.build(registered.userId(), registered.organizationId());
    }

    public void logout(HttpServletRequest request) {
        sessions.end(request);
    }

    /** @throws com.vendorflow.shared.error.NotFoundException (404) if not a member / nonexistent */
    public Me switchOrganization(UUID userId, UUID organizationId, HttpServletRequest request) {
        UUID previous = tenantContext.current().map(TenantContext.Tenant::organizationId).orElse(null);
        OrganizationSummary target = organizations.requireMembership(userId, organizationId);
        tx.executeWithoutResult(s -> {
            users.updateLastActiveOrganization(userId, target.id(), clock.instant());
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("from", previous == null ? null : previous.toString());
            metadata.put("to", target.id().toString());
            audit.record(target.id(), userId, "session.organization_switched", "organization", target.id(),
                    metadata);
        });
        HttpSession session = request.getSession(true);
        ActiveOrganizationSession.set(session, target.id());
        return meService.build(userId, target.id());
    }

    /** Passwords over 72 bytes can never have been set (signup rejects them); bcrypt would throw on them. */
    private static boolean fitsBcrypt(String password) {
        return password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= PasswordPolicy.MAX_BYTES;
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "invalid-credentials", "Authentication failed",
                "Invalid email or password.");
    }
}
