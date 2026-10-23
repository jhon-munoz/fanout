package com.caseware.fanout.resilience;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Stops dispatching after consecutive transient failures. After the open period it lets calls through
 * again (half-open): one success closes it, one failure reopens it. Concurrency during half-open is
 * still capped by the limiter.
 */
public final class CircuitBreaker {

    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final int failureThreshold;
    private final Duration openFor;
    private final Clock clock;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private Instant openedAt;

    public CircuitBreaker(int failureThreshold, Duration openFor, Clock clock) {
        this.failureThreshold = failureThreshold;
        this.openFor = openFor;
        this.clock = clock;
    }

    public synchronized boolean allowRequest() {
        if (state == State.OPEN) {
            if (clock.instant().isBefore(openedAt.plus(openFor))) {
                return false;
            }
            state = State.HALF_OPEN;
        }
        return true;
    }

    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        state = State.CLOSED;
    }

    public synchronized void recordFailure() {
        consecutiveFailures++;
        if (state == State.HALF_OPEN || consecutiveFailures >= failureThreshold) {
            state = State.OPEN;
            openedAt = clock.instant();
        }
    }

    public synchronized State state() {
        return state;
    }
}
