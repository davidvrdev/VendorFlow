package com.vendorflow.audit;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * HMAC-SHA256 of a client IP with a server secret. Raw IPs are never stored (docs/SECURITY.md section 5); the keyed
 * hash still lets us correlate events from one address, but an attacker with the database alone cannot brute-force
 * the small IPv4 space back to addresses.
 */
@Component
public class IpHasher {

    private final SecretKeySpec key;

    public IpHasher(@Value("${app.security.ip-hash-secret}") String secret) {
        if (secret == null || secret.length() < 16) {
            throw new IllegalStateException("app.security.ip-hash-secret must be at least 16 characters");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public String hash(String ip) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(ip.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
