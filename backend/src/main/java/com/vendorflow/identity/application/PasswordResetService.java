package com.vendorflow.identity.application;

import com.vendorflow.audit.AuditService;
import com.vendorflow.identity.domain.AppUser;
import com.vendorflow.identity.domain.UserToken;
import com.vendorflow.identity.infrastructure.AppUserRepository;
import com.vendorflow.identity.infrastructure.UserTokenRepository;
import com.vendorflow.notification.NotificationKind;
import com.vendorflow.notification.OutboxService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Forgot-password flow: request a link, then set a new password with it. */
@Service
public class PasswordResetService {

    static final Duration RESET_TTL = Duration.ofMinutes(30);

    private final AppUserRepository users;
    private final UserTokenRepository userTokens;
    private final TokenGenerator tokens;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final SessionService sessions;
    private final AuditService audit;
    private final OutboxService outbox;
    private final Clock clock;

    public PasswordResetService(AppUserRepository users, UserTokenRepository userTokens, TokenGenerator tokens,
            PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy, SessionService sessions,
            AuditService audit, OutboxService outbox, Clock clock) {
        this.users = users;
        this.userTokens = userTokens;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.sessions = sessions;
        this.audit = audit;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * The caller always gets the same 202, whether or not the address is registered. An unknown address only pays
     * a hash computation (the known path additionally writes two rows); the endpoint is also IP rate limited.
     */
    @Transactional
    public void request(String email) {
        Optional<AppUser> found = users.findByEmailIgnoreCase(email.trim());
        if (found.isEmpty()) {
            tokens.hash(tokens.newToken());
            return;
        }
        AppUser user = found.get();
        Instant now = clock.instant();
        userTokens.invalidateUnused(user.getId(), UserToken.Purpose.PASSWORD_RESET, now);
        String raw = tokens.newToken();
        UserToken token = userTokens.save(new UserToken(user.getId(), UserToken.Purpose.PASSWORD_RESET,
                tokens.hash(raw), now.plus(RESET_TTL), now));
        outbox.enqueue(NotificationKind.PASSWORD_RESET, null, user.getEmail(), "password-reset:" + token.getId(),
                Map.of("token", raw, "fullName", user.getFullName()));
        audit.record(null, user.getId(), "user.password_reset_requested", "user", user.getId(), Map.of());
    }

    /**
     * Everything below commits atomically, including the deletion of the user's sessions (Spring Session JDBC joins
     * this transaction): either the password changed AND every old session is gone, or nothing happened.
     * A weak password rolls back, so the link stays usable for a retry.
     */
    @Transactional
    public void confirm(String rawToken, String newPassword) {
        Instant now = clock.instant();
        UserToken token = userTokens.findByTokenHash(tokens.hash(rawToken))
                .filter(t -> t.getPurpose() == UserToken.Purpose.PASSWORD_RESET)
                .filter(t -> t.getUsedAt() == null && t.getExpiresAt().isAfter(now))
                .orElseThrow(InvalidLinkException::new);
        AppUser user = users.findById(token.getUserId()).orElseThrow(InvalidLinkException::new);
        passwordPolicy.validate(newPassword, user.getEmail(), "newPassword");
        if (userTokens.markUsed(token.getId(), now) != 1) {
            throw new InvalidLinkException();
        }
        user.changePassword(passwordEncoder.encode(newPassword), now);
        // Any other outstanding reset link for this user is now pointless (and would be a second way in).
        userTokens.invalidateUnused(user.getId(), UserToken.Purpose.PASSWORD_RESET, now);
        sessions.endAllForUser(user.getId());
        audit.record(null, user.getId(), "user.password_reset", "user", user.getId(), Map.of());
    }
}
