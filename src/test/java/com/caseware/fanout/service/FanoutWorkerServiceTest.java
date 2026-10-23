package com.caseware.fanout.service;

import com.caseware.fanout.configuration.WorkerConfig;
import com.caseware.fanout.model.EngagementRef;
import com.caseware.fanout.enums.ItemStatus;
import com.caseware.fanout.model.TemplateRef;
import com.caseware.fanout.exception.DownstreamException;
import com.caseware.fanout.exception.DownstreamException.Kind;
import com.caseware.fanout.client.EngagementTemplateClient;
import com.caseware.fanout.resilience.AdaptiveConcurrencyLimiter;
import com.caseware.fanout.resilience.CircuitBreaker;
import com.caseware.fanout.resilience.RetryPolicy;
import com.caseware.fanout.repository.InMemoryWorkQueueRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FanoutWorkerServiceTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private final InMemoryWorkQueueRepository store = new InMemoryWorkQueueRepository(3);
    private final Map<String, TemplateRef> index = new ConcurrentHashMap<>();
    private final AtomicInteger sinkWrites = new AtomicInteger();
    private final AtomicInteger calls = new AtomicInteger();

    private AdaptiveConcurrencyLimiter limiter;
    private FanoutWorkerService worker;

    @AfterEach
    void stop() throws InterruptedException {
        if (worker != null) {
            worker.close();
        }
    }

    private void startWorker(EngagementTemplateClient client, int maxAttempts, int initialLimit, int breakerThreshold) {
        limiter = new AdaptiveConcurrencyLimiter(1, initialLimit, initialLimit, Duration.ofSeconds(1),
                Clock.systemUTC());
        worker = new FanoutWorkerService(
                store,
                client,
                (engagement, template) -> {
                    sinkWrites.incrementAndGet();
                    index.put(engagement.engagementId(), template);
                },
                limiter,
                new CircuitBreaker(breakerThreshold, Duration.ofMinutes(10), Clock.systemUTC()),
                new RetryPolicy(maxAttempts, Duration.ofMillis(1), Duration.ofMillis(5)),
                new WorkerConfig(Duration.ofSeconds(30), Duration.ofMillis(2), Duration.ofSeconds(2)),
                Clock.systemUTC(),
                new Random(1));
        worker.start();
    }

    private static List<EngagementRef> engagements(int count, int firms) {
        List<EngagementRef> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new EngagementRef("e" + i, "firm-" + (i % firms)));
        }
        return list;
    }

    private void awaitSettled(int expected) throws InterruptedException {
        com.caseware.fanout.testsupport.Eventually.await(WAIT,
                () -> store.stats().done() + store.stats().dead() == expected);
    }

    @Test
    void neverExceedsTheConcurrencyBudget() throws InterruptedException {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        store.enqueue(engagements(30, 3));

        startWorker(engagement -> {
            int now = inFlight.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            return new TemplateRef("tmpl", "v1");
        }, 3, 4, 100);
        awaitSettled(30);

        assertEquals(30, store.stats().done());
        assertTrue(peak.get() <= 4, "peak was " + peak.get());
        assertTrue(peak.get() >= 2, "expected some parallelism, peak was " + peak.get());
    }

    @Test
    void retriesTransientFailuresThenRecordsExactlyOnce() throws InterruptedException {
        store.enqueue(engagements(1, 1));

        startWorker(engagement -> {
            if (calls.incrementAndGet() < 3) {
                throw new DownstreamException(Kind.TRANSIENT, "timeout");
            }
            return new TemplateRef("tmpl", "v1");
        }, 3, 2, 100);
        awaitSettled(1);

        assertEquals(ItemStatus.DONE, store.snapshot("e0").orElseThrow().status());
        assertEquals(3, calls.get());
        assertEquals(1, sinkWrites.get());
    }

    @Test
    void permanentFailureIsDeadWithoutRetry() throws InterruptedException {
        store.enqueue(engagements(1, 1));

        startWorker(engagement -> {
            calls.incrementAndGet();
            throw new DownstreamException(Kind.PERMANENT, "engagement deleted");
        }, 3, 2, 100);
        awaitSettled(1);

        assertEquals(ItemStatus.DEAD, store.snapshot("e0").orElseThrow().status());
        assertEquals(1, calls.get());
        assertEquals(0, sinkWrites.get());
    }

    @Test
    void exhaustedRetriesEndInDead() throws InterruptedException {
        store.enqueue(engagements(1, 1));

        startWorker(engagement -> {
            calls.incrementAndGet();
            throw new DownstreamException(Kind.TRANSIENT, "timeout");
        }, 3, 2, 100);
        awaitSettled(1);

        assertEquals(ItemStatus.DEAD, store.snapshot("e0").orElseThrow().status());
        assertEquals(3, calls.get());
    }

    @Test
    void throttlingShrinksTheBudgetAndDoesNotBurnAttempts() throws InterruptedException {
        store.enqueue(engagements(1, 1));

        startWorker(engagement -> {
            if (calls.incrementAndGet() == 1) {
                throw new DownstreamException(Kind.THROTTLED, "429", null, Duration.ofMillis(1));
            }
            return new TemplateRef("tmpl", "v1");
        }, 1, 8, 100);
        awaitSettled(1);

        assertEquals(ItemStatus.DONE, store.snapshot("e0").orElseThrow().status());
        assertEquals(1, worker.stats().throttled());
        assertTrue(limiter.limit() < 8, "limit was " + limiter.limit());
    }

    @Test
    void openBreakerStopsHammeringAFailingDownstream() throws InterruptedException {
        store.enqueue(engagements(20, 2));

        startWorker(engagement -> {
            calls.incrementAndGet();
            throw new DownstreamException(Kind.TRANSIENT, "down");
        }, 100, 2, 2);
        com.caseware.fanout.testsupport.Eventually.await(WAIT, () -> calls.get() >= 2);
        Thread.sleep(200);

        assertTrue(calls.get() <= 6, "calls were " + calls.get());
        assertEquals(0, store.stats().done());
    }
}
