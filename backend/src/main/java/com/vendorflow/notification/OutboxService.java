package com.vendorflow.notification;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Producer side of the outbox. Joins the caller's transaction (MANDATORY), so an email exists if and only if the
 * business change that caused it committed.
 *
 * <p>Idempotency: a native {@code INSERT ... ON CONFLICT (idempotency_key) DO NOTHING}. A duplicate is a silent
 * no-op and, unlike catching a unique-violation exception, does not abort the surrounding Postgres transaction.
 */
@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    /** Account emails reachable by anonymous callers (signup, reset, resend): throttled per recipient address. */
    static final Set<NotificationKind> THROTTLED_KINDS =
            Set.of(NotificationKind.EMAIL_VERIFICATION, NotificationKind.PASSWORD_RESET);
    static final int MAX_ACCOUNT_EMAILS_PER_RECIPIENT = 3;
    static final Duration ACCOUNT_EMAIL_WINDOW = Duration.ofHours(1);

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public OutboxService(JdbcClient jdbc, JsonMapper jsonMapper, Clock clock) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /**
     * @param organizationId null for account emails (verification, reset)
     * @param payload minimal template data; may hold a raw token, which the dispatcher scrubs after delivery
     * @return true if a row was created, false if the idempotency key already existed or the recipient exceeded the
     *         account-email throttle (3 verification/reset emails per address per hour; excess is silently dropped so
     *         API responses do not change, and cannot be used to flood a victim's mailbox)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean enqueue(NotificationKind kind, UUID organizationId, String recipientEmail, String idempotencyKey,
            Map<String, Object> payload) {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (THROTTLED_KINDS.contains(kind) && recentAccountEmails(recipientEmail, now) >= MAX_ACCOUNT_EMAILS_PER_RECIPIENT) {
            // Only the kind and the domain are logged: the address itself is personal data.
            log.info("Account email throttled: kind={} recipientDomain={}", kind, domainOf(recipientEmail));
            return false;
        }
        int inserted = jdbc.sql("""
                INSERT INTO notification (id, organization_id, kind, recipient_email, idempotency_key, payload,
                                          status, attempts, next_attempt_at, created_at, updated_at)
                VALUES (:id, CAST(:org AS uuid), :kind, :recipient, :key, CAST(:payload AS jsonb),
                        'PENDING', 0, :now, :now, :now)
                ON CONFLICT (idempotency_key) DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("org", organizationId == null ? null : organizationId.toString())
                .param("kind", kind.name())
                .param("recipient", recipientEmail)
                .param("key", idempotencyKey)
                .param("payload", jsonMapper.writeValueAsString(payload))
                .param("now", now)
                .update();
        return inserted == 1;
    }

    private int recentAccountEmails(String recipientEmail, OffsetDateTime now) {
        return jdbc.sql("""
                SELECT count(*) FROM notification
                WHERE lower(recipient_email) = :recipient AND kind IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET')
                  AND created_at > :since
                """)
                .param("recipient", recipientEmail.toLowerCase(Locale.ROOT))
                .param("since", now.minus(ACCOUNT_EMAIL_WINDOW))
                .query(Integer.class).single();
    }

    private static String domainOf(String email) {
        int at = email.lastIndexOf('@');
        return at < 0 ? "unknown" : email.substring(at + 1).toLowerCase(Locale.ROOT);
    }
}
