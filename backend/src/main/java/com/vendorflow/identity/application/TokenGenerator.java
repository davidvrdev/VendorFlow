package com.vendorflow.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/** 256-bit random tokens for emailed links; only the SHA-256 hash is stored, the raw value goes in the email. */
@Component
public class TokenGenerator {

    private final SecureRandom random = new SecureRandom();

    /** 32 random bytes, base64url without padding (43 chars). */
    public String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Hex SHA-256. A fast hash is right here: the input is already 256 bits of entropy (no brute force). */
    public String hash(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
