package com.vendorflow.notification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Global (cross-organization) suppression list (ADR-0012). An address that unsubscribed from automated chasing must not
 * get chases, manual document requests or portal-link emails from ANY organization. Only the SHA-256 of the trimmed,
 * lower-cased address is stored, so the table holds no plaintext addresses. The hash is unsalted on purpose: lookups
 * must work from the plain address; the table is not exposed through any API.
 */
@Service
public class EmailSuppressionService {

    public static final String REASON_CHASING_OPT_OUT = "CHASING_OPT_OUT";

    private final JdbcClient jdbc;
    private final Clock clock;

    public EmailSuppressionService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Hex SHA-256 of the trimmed, lower-cased address. */
    public static String hash(String email) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(email.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    @Transactional(readOnly = true)
    public boolean isSuppressed(String email) {
        return email != null && !email.isBlank() && jdbc.sql("select count(*) from email_suppression where email_hash = :h")
                .param("h", hash(email)).query(Integer.class).single() > 0;
    }

    /** The subset of the given hashes that is suppressed: one query for a whole run. */
    @Transactional(readOnly = true)
    public Set<String> suppressedAmong(Collection<String> hashes) {
        if (hashes.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbc.sql("select email_hash from email_suppression where email_hash in (:h)")
                .param("h", hashes).query(String.class).list());
    }

    /** Idempotent. Joins the caller's transaction so the suppression commits together with the opt-out. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void suppressHash(String emailHash, String reason) {
        jdbc.sql("""
                insert into email_suppression (email_hash, reason, created_at) values (:h, :reason, :now)
                on conflict (email_hash) do nothing""")
                .param("h", emailHash).param("reason", reason)
                .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).update();
    }
}
