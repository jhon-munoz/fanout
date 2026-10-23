package com.caseware.fanout.resilience;

import java.time.Duration;
import java.util.random.RandomGenerator;

public record RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay) {

    public boolean exhausted(int attempt) {
        return attempt >= maxAttempts;
    }

    public Duration delay(int attempt, RandomGenerator random, Duration retryAfter) {
        int exponent = Math.min(Math.max(attempt - 1, 0), 30);
        long ceilingNanos = Math.min(maxDelay.toNanos(), baseDelay.toNanos() << exponent);
        Duration jittered = Duration.ofNanos(random.nextLong(ceilingNanos + 1));
        return retryAfter != null && retryAfter.compareTo(jittered) > 0 ? retryAfter : jittered;
    }
}
