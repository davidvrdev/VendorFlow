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
 * NIST 800-63B style: length 12 to 128 characters (Argon2id has no 72-byte limit), not the account email, not in the
 * top-100k common/breached list (security/common-passwords.txt, see DECISIONS.md). No
 * composition rules (they push users to predictable patterns). Comparison is case-insensitive.
 */
@Component
public class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 128;
    /** Hashes created before the Argon2id migration are bcrypt, which cannot take more than 72 bytes (see AuthService). */
    public static final int BCRYPT_MAX_BYTES = 72;

    private final Set<String> common;

    public PasswordPolicy() {
        this.common = load();
    }

    /** @throws RequestValidationException (400) with a violation on field {@code password} */
    public void validate(String password, String email) {
        validate(password, email, "password");
    }

    /** Same rules, reporting the violation on the given request field (e.g. newPassword). */
    public void validate(String password, String email, String field) {
        if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            throw violation(field, "must be between " + MIN_LENGTH + " and " + MAX_LENGTH + " characters");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (email != null && lower.equals(email.trim().toLowerCase(Locale.ROOT))) {
            throw violation(field, "must not be the same as your email");
        }
        if (common.contains(lower)) {
            throw violation(field, "is too common; choose something less guessable");
        }
    }

    private static RequestValidationException violation(String field, String message) {
        return new RequestValidationException(java.util.List.of(new FieldViolation(field, message)));
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
