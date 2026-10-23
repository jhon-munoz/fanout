package com.caseware.fanout.resilience;

import com.caseware.fanout.resilience.AdaptiveConcurrencyLimiter.Outcome;
import com.caseware.fanout.testsupport.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResilienceTest {

    private final TestClock clock = new TestClock();

    @Test
    void limiterNeverExceedsItsLimit() {
        var limiter = new AdaptiveConcurrencyLimiter(1, 2, 4, Duration.ofSeconds(1), clock);

        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());
        limiter.release(Outcome.NEUTRAL);
        assertTrue(limiter.tryAcquire());
    }

    @Test
    void limiterHalvesOncePerCooldownAndNeverGoesBelowMin() {
        var limiter = new AdaptiveConcurrencyLimiter(2, 8, 8, Duration.ofSeconds(10), clock);
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire();
        }

        limiter.release(Outcome.THROTTLED);
        limiter.release(Outcome.THROTTLED);
        assertEquals(4, limiter.limit());

        clock.advance(Duration.ofSeconds(10));
        limiter.release(Outcome.THROTTLED);
        assertEquals(2, limiter.limit());

        clock.advance(Duration.ofSeconds(10));
        limiter.tryAcquire();
        limiter.release(Outcome.THROTTLED);
        assertEquals(2, limiter.limit());
    }

    @Test
    void limiterGrowsByOneAfterAFullWindowOfSuccesses() {
        var limiter = new AdaptiveConcurrencyLimiter(1, 2, 4, Duration.ofSeconds(1), clock);

        for (int i = 0; i < 2; i++) {
            limiter.tryAcquire();
            limiter.release(Outcome.SUCCESS);
        }

        assertEquals(3, limiter.limit());
    }

    @Test
    void breakerOpensThenHalfOpensThenClosesOnSuccess() {
        var breaker = new CircuitBreaker(2, Duration.ofSeconds(30), clock);

        breaker.recordFailure();
        assertTrue(breaker.allowRequest());
        breaker.recordFailure();
        assertFalse(breaker.allowRequest());

        clock.advance(Duration.ofSeconds(30));
        assertTrue(breaker.allowRequest());
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state());

        breaker.recordSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    void breakerReopensOnAFailureWhileHalfOpen() {
        var breaker = new CircuitBreaker(1, Duration.ofSeconds(30), clock);
        breaker.recordFailure();
        clock.advance(Duration.ofSeconds(30));
        breaker.allowRequest();

        breaker.recordFailure();

        assertFalse(breaker.allowRequest());
    }

    @Test
    void retryDelayIsJitteredBoundedAndRespectsRetryAfter() {
        var policy = new RetryPolicy(5, Duration.ofSeconds(1), Duration.ofSeconds(8));
        var random = new Random(42);

        for (int attempt = 1; attempt <= 10; attempt++) {
            Duration delay = policy.delay(attempt, random, null);
            assertTrue(delay.compareTo(Duration.ofSeconds(8)) <= 0);
        }
        assertEquals(Duration.ofSeconds(60), policy.delay(1, random, Duration.ofSeconds(60)));
        assertTrue(policy.exhausted(5));
        assertFalse(policy.exhausted(4));
    }
}
