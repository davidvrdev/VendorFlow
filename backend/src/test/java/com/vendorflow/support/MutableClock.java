package com.vendorflow.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * System clock plus an adjustable offset: tests call {@link #advance(Duration)} to simulate time passing (lock expiry,
 * outbox backoff) and the base class resets it before every test. Thread-safe (volatile).
 */
public class MutableClock extends Clock {

    private volatile Duration offset = Duration.ZERO;

    public void advance(Duration duration) {
        offset = offset.plus(duration);
    }

    public void reset() {
        offset = Duration.ZERO;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    /**
     * A view of this clock in another zone (same instants, including later advance() calls). The compliance engine
     * computes "today" with {@code LocalDate.now(clock.withZone(orgZone))}, so the zone must be honored.
     */
    @Override
    public Clock withZone(ZoneId zone) {
        MutableClock parent = this;
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return zone;
            }

            @Override
            public Clock withZone(ZoneId other) {
                return parent.withZone(other);
            }

            @Override
            public Instant instant() {
                return parent.instant();
            }
        };
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(offset);
    }
}
