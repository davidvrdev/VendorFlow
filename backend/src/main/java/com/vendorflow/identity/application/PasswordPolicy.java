package com.vendorflow.identity.application;

import com.vendorflow.shared.error.FieldViolation;
import com.vendorflow.shared.error.RequestValidationException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * NIST 800-63B style: length 12 to 128, not the account email, not in a list of very common passwords. No
 * composition rules (they push users to predictable patterns). Comparison is case-insensitive.
 */
@Component
public class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 128;
    /** bcrypt ignores/rejects input beyond 72 bytes (Spring Security throws), so we reject it up front. */
    public static final int MAX_BYTES = 72;

    private final Set<String> common;

    public PasswordPolicy() {
        this.common = load();
    }

    /** @throws RequestValidationException (400) with a violation on field {@code password} */
    public void validate(String password, String email) {
        if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            throw violation("must be between " + MIN_LENGTH + " and " + MAX_LENGTH + " characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw violation("must be at most " + MAX_BYTES + " bytes (bcrypt limit; non-ASCII characters use several bytes)");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (email != null && lower.equals(email.trim().toLowerCase(Locale.ROOT))) {
            throw violation("must not be the same as your email");
        }
        if (common.contains(lower)) {
            throw violation("is too common; choose something less guessable");
        }
    }

    private static RequestValidationException violation(String message) {
        return new RequestValidationException(java.util.List.of(new FieldViolation("password", message)));
    }

    private static Set<String> load() {
        Set<String> result = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("security/common-passwords.txt").getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String entry = line.trim().toLowerCase(Locale.ROOT);
                if (!entry.isEmpty()) {
                    result.add(entry);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load common-passwords.txt", e);
        }
        return Set.copyOf(result);
    }
}
