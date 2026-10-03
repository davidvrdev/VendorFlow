package com.vendorflow.identity.application;

import com.vendorflow.audit.AuditService;
import com.vendorflow.identity.domain.AppUser;
import com.vendorflow.identity.domain.UserToken;
import com.vendorflow.identity.infrastructure.AppUserRepository;
import com.vendorflow.identity.infrastructure.UserTokenRepository;
import com.vendorflow.notification.NotificationKind;
import com.vendorflow.notification.OutboxService;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Confirming a mailbox via the emailed link, and re-sending that link. */
@Service
public class EmailVerificationService {

    private final AppUserRepository users;
    private final UserTokenRepository userTokens;
    private final TokenGenerator tokens;
    private final AuditService audit;
    private final OutboxService outbox;
    private final Clock clock;

    public EmailVerificationService(AppUserRepository users, UserTokenRepository userTokens, TokenGenerator tokens,
            AuditService audit, OutboxService outbox, Clock clock) {
        this.users = users;
        this.userTokens = userTokens;
        this.tokens = tokens;
        this.audit = audit;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Public endpoint: the token is the credential. A user who is already verified and presents a valid token still
     * succeeds (idempotent), so opening a link twice (two tabs, a double click) is harmless.
     *
     * @throws InvalidLinkException (400) for any unusable token, always with the same body
     */
    @Transactional
    public void verify(String rawToken) {
        Instant now = clock.instant();
        UserToken token = userTokens.findByTokenHash(tokens.hash(rawToken))
                .filter(t -> t.getPurpose() == UserToken.Purpose.EMAIL_VERIFICATION)
                .filter(t -> t.getUsedAt() == null && t.getExpiresAt().isAfter(now))
                .orElseThrow(InvalidLinkException::new);
        // Atomic claim: of two concurrent requests with the same token only one gets 1 here.
        if (userTokens.markUsed(token.getId(), now) != 1) {
            throw new InvalidLinkException();
        }
        if (users.markEmailVerified(token.getUserId(), now) == 1) {
            audit.record(null, token.getUserId(), "user.email_verified", "user", token.getUserId(), Map.of());
        }
    }

    /** Authenticated. No-op for a verified user; otherwise the previous links die and a fresh 24 h one is mailed. */
    @Transactional
    public void resend() {
        UUID userId = CurrentUser.id();
        AppUser user = users.findById(userId).orElseThrow(() -> new BadCredentialsException("Unknown user"));
        if (user.isEmailVerified()) {
            return;
        }
        Instant now = clock.instant();
        userTokens.invalidateUnused(userId, UserToken.Purpose.EMAIL_VERIFICATION, now);
        String raw = tokens.newToken();
        UserToken token = userTokens.save(new UserToken(userId, UserToken.Purpose.EMAIL_VERIFICATION,
                tokens.hash(raw), now.plus(AccountRegistrationService.VERIFICATION_TTL), now));
        outbox.enqueue(NotificationKind.EMAIL_VERIFICATION, null, user.getEmail(),
                "email-verification:" + token.getId(), Map.of("token", raw, "fullName", user.getFullName()));
    }
}
