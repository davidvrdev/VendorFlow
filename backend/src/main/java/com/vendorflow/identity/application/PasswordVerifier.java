package com.vendorflow.identity.application;

import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * One place that verifies a password against a stored hash. Two quirks of the migration from bcrypt to Argon2id
 * (ASVS V2.1.2) live here so callers cannot forget them:
 * <ul>
 *   <li>bcrypt cannot take more than 72 bytes (Spring Security throws). A legacy {bcrypt} hash can never belong to such
 *       a password (the old policy refused them), so a longer candidate simply does not match, after the same amount
 *       of work as any other mismatch.</li>
 *   <li>{@link #needsUpgrade} tells the login flow to re-hash a legacy hash with the current default algorithm.</li>
 * </ul>
 */
@Component
public class PasswordVerifier {

    private static final String BCRYPT_PREFIX = "{bcrypt}";

    private final PasswordEncoder encoder;

    public PasswordVerifier(PasswordEncoder encoder) {
        this.encoder = encoder;
    }

    public boolean matches(String raw, String storedHash) {
        if (storedHash.startsWith(BCRYPT_PREFIX)
                && raw.getBytes(StandardCharsets.UTF_8).length > PasswordPolicy.BCRYPT_MAX_BYTES) {
            // Burn a verification anyway so the response time does not reveal which kind of hash this account has.
            encoder.matches("x", storedHash);
            return false;
        }
        return encoder.matches(raw, storedHash);
    }

    public boolean needsUpgrade(String storedHash) {
        return encoder.upgradeEncoding(storedHash);
    }

    public String encode(String raw) {
        return encoder.encode(raw);
    }
}
