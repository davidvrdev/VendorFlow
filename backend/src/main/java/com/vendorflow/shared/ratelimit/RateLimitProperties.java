package com.vendorflow.shared.ratelimit;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code app.rate-limit.*}. Defaults match docs/API.md; tests and operators can override per rule:
 * {@code app.rate-limit.limits.login=3}.
 */
@Component
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;
    private Duration window = Duration.ofMinutes(1);
    private Map<String, Integer> limits = new LinkedHashMap<>(Map.ofEntries(
            Map.entry("login", 10),
            Map.entry("signup", 5),
            Map.entry("password-reset-request", 5),
            Map.entry("invitation", 20),
            Map.entry("resend-verification", 3),
            Map.entry("token-redemption", 20),
            Map.entry("document-upload", 30),
            Map.entry("document-upload-user", 30),
            Map.entry("document-request-user", 30),
            Map.entry("vendor-import-preview-user", 10),
            Map.entry("vendor-export-user", 10),
            Map.entry("stripe-webhook", 600)));
    /** Per-rule window overriding {@link #window} (the CSV export is 10 per 10 minutes, not per minute). */
    private Map<String, Duration> windows = new LinkedHashMap<>(
            Map.of("vendor-export-user", Duration.ofMinutes(10)));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getWindow() {
        return window;
    }

    public void setWindow(Duration window) {
        this.window = window;
    }

    public Map<String, Duration> getWindows() {
        return windows;
    }

    public void setWindows(Map<String, Duration> windows) {
        this.windows = windows;
    }

    public Duration windowFor(String rule) {
        return windows.getOrDefault(rule, window);
    }

    public Map<String, Integer> getLimits() {
        return limits;
    }

    public void setLimits(Map<String, Integer> limits) {
        this.limits = limits;
    }
}
