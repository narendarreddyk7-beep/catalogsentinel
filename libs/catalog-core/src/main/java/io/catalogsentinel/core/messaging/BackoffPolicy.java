package io.catalogsentinel.core.messaging;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/**
 * Exponential backoff with "full jitter" (AWS Architecture Blog, "Exponential Backoff And Jitter"):
 * {@code delay = random(0, min(cap, base * 2^(attempt - 1)))}. Full jitter spreads retries of many failed
 * messages over the whole window, so a recovering dependency is not hit by synchronized retry waves.
 */
public final class BackoffPolicy {

    private final Duration base;
    private final Duration cap;
    private final DoubleSupplier random;

    public BackoffPolicy(Duration base, Duration cap, DoubleSupplier random) {
        if (base.isNegative() || base.isZero() || cap.compareTo(base) < 0) {
            throw new IllegalArgumentException("require 0 < base <= cap");
        }
        this.base = base;
        this.cap = cap;
        this.random = random;
    }

    public static BackoffPolicy fullJitter(Duration base, Duration cap) {
        return new BackoffPolicy(base, cap, () -> java.util.concurrent.ThreadLocalRandom.current().nextDouble());
    }

    /** Upper bound of the delay for an attempt (1-based), before jitter. */
    public Duration ceiling(int attempt) {
        int exponent = Math.min(Math.max(attempt, 1) - 1, 30);
        long millis = base.toMillis() * (1L << exponent);
        return Duration.ofMillis(Math.min(cap.toMillis(), millis));
    }

    public Duration delay(int attempt) {
        long ceilingMillis = ceiling(attempt).toMillis();
        return Duration.ofMillis((long) Math.floor(random.getAsDouble() * (ceilingMillis + 1)));
    }
}
