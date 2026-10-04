package com.vendorflow.shared.ratelimit;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fixed-window counter keyed by (rule, client IP).
 *
 * <p><b>Limitation (documented, accepted):</b> state is in this JVM's memory, so with N API instances the effective
 * limit is N times higher, and a restart resets it. The per-ACCOUNT lockout (in Postgres) is the control that holds
 * across instances. When we run more than one instance, move these counters to Postgres (docs/SECURITY.md section 2).
 * A fixed window also allows a burst of 2x the limit across a window boundary; fine for abuse damping.
 */
@Component
public class RateLimiter {

    /** Outcome of {@link #tryAcquire}: allowed, or denied with the seconds until the window resets. */
    public record Decision(boolean allowed, long retryAfterSeconds) {
    }

    private record Window(long startMillis, long windowMillis, int count) {
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final RateLimitProperties properties;
    private final Clock clock;

    public RateLimiter(RateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public Decision tryAcquire(String rule, String clientIp) {
        Integer limit = properties.getLimits().get(rule);
        if (!properties.isEnabled() || limit == null) {
            return new Decision(true, 0);
        }
        long now = clock.millis();
        long windowMillis = properties.windowFor(rule).toMillis();
        Window updated = windows.compute(rule + "|" + clientIp, (key, current) -> {
            if (current == null || now - current.startMillis() >= windowMillis) {
                return new Window(now, windowMillis, 1);
            }
            return new Window(current.startMillis(), windowMillis, current.count() + 1);
        });
        if (updated.count() <= limit) {
            return new Decision(true, 0);
        }
        long remainingMillis = updated.startMillis() + windowMillis - now;
        return new Decision(false, Math.max(1, (remainingMillis + 999) / 1000));
    }

    /** Drops expired windows so the map cannot grow without bound. */
    @Scheduled(fixedDelay = 60_000)
    void cleanup() {
        long now = clock.millis();
        windows.values().removeIf(w -> now - w.startMillis() >= w.windowMillis());
    }

    /** Clears all counters. Used by tests to isolate scenarios. */
    public void reset() {
        windows.clear();
    }
}
