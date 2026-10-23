package com.caseware.fanout.resilience;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Global in-flight budget for the downstream. Additive increase after a full window of successes,
 * multiplicative decrease when the downstream throttles us, at most once per cooldown so a burst of
 * throttles from already in-flight calls does not collapse the limit.
 */
public final class AdaptiveConcurrencyLimiter {

    public enum Outcome {
        SUCCESS,
        THROTTLED,
        NEUTRAL
    }

    private final int min;
    private final int max;
    private final Duration decreaseCooldown;
    private final Clock clock;

    private int limit;
    private int inFlight;
    private int successesSinceChange;
    private Instant lastDecrease;

    public AdaptiveConcurrencyLimiter(int min, int initial, int max, Duration decreaseCooldown, Clock clock) {
        if (min < 1 || initial < min || max < initial) {
            throw new IllegalArgumentException("require 1 <= min <= initial <= max");
        }
        this.min = min;
        this.limit = initial;
        this.max = max;
        this.decreaseCooldown = decreaseCooldown;
        this.clock = clock;
    }

    public synchronized boolean tryAcquire() {
        if (inFlight >= limit) {
            return false;
        }
        inFlight++;
        return true;
    }

    public synchronized void release(Outcome outcome) {
        inFlight--;
        switch (outcome) {
            case SUCCESS -> {
                successesSinceChange++;
                if (successesSinceChange >= limit && limit < max) {
                    limit++;
                    successesSinceChange = 0;
                }
            }
            case THROTTLED -> {
                Instant now = clock.instant();
                if (lastDecrease == null || !now.isBefore(lastDecrease.plus(decreaseCooldown))) {
                    limit = Math.max(min, limit / 2);
                    lastDecrease = now;
                    successesSinceChange = 0;
                }
            }
            case NEUTRAL -> {
            }
        }
    }

    public synchronized int limit() {
        return limit;
    }

    public synchronized int inFlight() {
        return inFlight;
    }
}
