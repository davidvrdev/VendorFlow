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
    private Map<String, Integer> limits = new LinkedHashMap<>(Map.of(
            "login", 10,
            "signup", 5,
            "password-reset-request", 5,
            "invitation", 20,
            "resend-verification", 3,
            "document-upload", 30,
            "document-upload-user", 30));

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

    public Map<String, Integer> getLimits() {
        return limits;
    }

    public void setLimits(Map<String, Integer> limits) {
        this.limits = limits;
    }
}
