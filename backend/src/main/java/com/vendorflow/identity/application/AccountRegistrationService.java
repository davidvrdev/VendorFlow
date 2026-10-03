package com.vendorflow.identity.application;

import com.vendorflow.audit.AuditService;
import com.vendorflow.identity.api.SignupRequest;
import com.vendorflow.identity.domain.AppUser;
import com.vendorflow.identity.domain.UserToken;
import com.vendorflow.identity.infrastructure.AppUserRepository;
import com.vendorflow.identity.infrastructure.UserTokenRepository;
import com.vendorflow.notification.NotificationKind;
import com.vendorflow.notification.OutboxService;
import com.vendorflow.organization.application.OrganizationService;
import com.vendorflow.shared.error.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Signup in ONE transaction: user, organization, OWNER membership, audit events, verification token and the
 * outbox email either all exist or none do. Starting the session happens afterwards (AuthService).
 */
@Service
public class AccountRegistrationService {

    static final Duration VERIFICATION_TTL = Duration.ofHours(24);

    public record Registered(UUID userId, UUID organizationId) {
    }

    private final AppUserRepository users;
    private final UserTokenRepository userTokens;
    private final OrganizationService organizations;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final TokenGenerator tokens;
    private final AuditService audit;
    private final OutboxService outbox;
    private final Clock clock;

    public AccountRegistrationService(AppUserRepository users, UserTokenRepository userTokens,
            OrganizationService organizations, PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy,
            TokenGenerator tokens, AuditService audit, OutboxService outbox, Clock clock) {
        this.users = users;
        this.userTokens = userTokens;
        this.organizations = organizations;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.tokens = tokens;
        this.audit = audit;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public Registered register(SignupRequest request) {
        String email = request.email().trim();
        passwordPolicy.validate(request.password(), email);
        if (users.findByEmailIgnoreCase(email).isPresent()) {
            throw emailTaken();
        }
        Instant now = clock.instant();
        AppUser user = new AppUser(email, request.fullName().trim(), passwordEncoder.encode(request.password()), now);
        try {
            // Flush now so a concurrent duplicate surfaces here (unique index on lower(email)) as a clean 409.
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw emailTaken();
        }
        UUID organizationId = organizations.createWithOwner(request.organizationName(), user.getId());
        user.rememberActiveOrganization(organizationId);
        audit.record(organizationId, user.getId(), "user.signed_up", "user", user.getId(),
                Map.of("organizationId", organizationId.toString()));

        String rawToken = tokens.newToken();
        UserToken token = userTokens.save(new UserToken(user.getId(), UserToken.Purpose.EMAIL_VERIFICATION,
                tokens.hash(rawToken), now.plus(VERIFICATION_TTL), now));
        outbox.enqueue(NotificationKind.EMAIL_VERIFICATION, null, email, "email-verification:" + token.getId(),
                Map.of("token", rawToken, "fullName", user.getFullName()));
        return new Registered(user.getId(), organizationId);
    }

    private static ApiException emailTaken() {
        return new ApiException(HttpStatus.CONFLICT, "email-registered", "Email already registered",
                "An account with this email already exists.");
    }
}
