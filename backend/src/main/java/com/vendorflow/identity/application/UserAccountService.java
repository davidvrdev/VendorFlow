package com.vendorflow.identity.application;

import com.vendorflow.identity.domain.AppUser;
import com.vendorflow.identity.infrastructure.AppUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What other features (organization/invitations) may ask of the identity feature, so they never touch
 * AppUserRepository themselves. Methods join the caller's transaction.
 */
@Service
public class UserAccountService {

    public record UserSummary(UUID id, String email, String fullName, boolean emailVerified) {
    }

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final Clock clock;

    public UserAccountService(AppUserRepository users, PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy,
            Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<UserSummary> findByEmail(String email) {
        return users.findByEmailIgnoreCase(email.trim()).map(UserAccountService::summary);
    }

    /** @throws BadCredentialsException (401) if the user no longer exists (stale session) */
    @Transactional(readOnly = true)
    public UserSummary require(UUID userId) {
        return users.findById(userId).map(UserAccountService::summary)
                .orElseThrow(() -> new BadCredentialsException("Unknown user"));
    }

    /**
     * Creates an account whose mailbox is already proven (invitation token). Validates the password policy.
     *
     * @throws org.springframework.dao.DataIntegrityViolationException if the email is taken (race with a signup)
     */
    @Transactional
    public UUID createVerifiedUser(String email, String fullName, String password) {
        passwordPolicy.validate(password, email);
        Instant now = clock.instant();
        AppUser user = new AppUser(email.trim(), fullName.trim(), passwordEncoder.encode(password), now);
        user.markEmailVerified(now);
        users.saveAndFlush(user);
        return user.getId();
    }

    @Transactional
    public void rememberActiveOrganization(UUID userId, UUID organizationId) {
        users.updateLastActiveOrganization(userId, organizationId, clock.instant());
    }

    /** When someone leaves/is removed from the organization that was their "last active" one. */
    @Transactional
    public void clearLastActiveOrganization(UUID userId, UUID organizationId) {
        users.clearLastActiveOrganization(userId, organizationId, clock.instant());
    }

    private static UserSummary summary(AppUser u) {
        return new UserSummary(u.getId(), u.getEmail(), u.getFullName(), u.isEmailVerified());
    }
}
