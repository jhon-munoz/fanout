package com.caseware.fanout.service;

import com.caseware.fanout.configuration.WorkerConfig;
import com.caseware.fanout.model.Lease;
import com.caseware.fanout.model.TemplateRef;
import com.caseware.fanout.model.WorkerStats;
import com.caseware.fanout.exception.DownstreamException;
import com.caseware.fanout.client.EngagementTemplateClient;
import com.caseware.fanout.repository.TemplateIndexRepository;
import com.caseware.fanout.repository.WorkQueueRepository;
import com.caseware.fanout.resilience.AdaptiveConcurrencyLimiter;
import com.caseware.fanout.resilience.AdaptiveConcurrencyLimiter.Outcome;
import com.caseware.fanout.resilience.CircuitBreaker;
import com.caseware.fanout.resilience.RetryPolicy;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.random.RandomGenerator;

public final class FanoutWorkerService implements AutoCloseable {

    private final WorkQueueRepository store;
    private final EngagementTemplateClient client;
    private final TemplateIndexRepository sink;
    private final AdaptiveConcurrencyLimiter limiter;
    private final CircuitBreaker breaker;
    private final RetryPolicy retryPolicy;
    private final WorkerConfig config;
    private final Clock clock;
    private final RandomGenerator random;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread dispatcher;

    private final LongAdder succeeded = new LongAdder();
    private final LongAdder retried = new LongAdder();
    private final LongAdder throttled = new LongAdder();
    private final LongAdder dead = new LongAdder();
    private final LongAdder fenced = new LongAdder();
    private final LongAdder internalErrors = new LongAdder();

    public FanoutWorkerService(WorkQueueRepository store, EngagementTemplateClient client, TemplateIndexRepository sink,
                        AdaptiveConcurrencyLimiter limiter, CircuitBreaker breaker, RetryPolicy retryPolicy,
                        WorkerConfig config, Clock clock, RandomGenerator random) {
        this.store = store;
        this.client = client;
        this.sink = sink;
        this.limiter = limiter;
        this.breaker = breaker;
        this.retryPolicy = retryPolicy;
        this.config = config;
        this.clock = clock;
        this.random = random;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("already started");
        }
        dispatcher = Thread.ofPlatform().name("fanout-dispatcher").start(this::dispatchLoop);
    }

    public WorkerStats stats() {
        return new WorkerStats(succeeded.sum(), retried.sum(), throttled.sum(), dead.sum(), fenced.sum(),
                internalErrors.sum());
    }

    @Override
    public void close() throws InterruptedException {
        running.set(false);
        if (dispatcher != null) {
            dispatcher.interrupt();
            dispatcher.join();
        }
        executor.shutdown();
        if (!executor.awaitTermination(config.shutdownGrace().toMillis(), TimeUnit.MILLISECONDS)) {
            executor.shutdownNow();
        }
    }

    private void dispatchLoop() {
        while (running.get()) {
            if (dispatchOne()) {
                continue;
            }
            try {
                Thread.sleep(config.pollInterval().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private boolean dispatchOne() {
        if (!breaker.allowRequest() || !limiter.tryAcquire()) {
            return false;
        }
        Optional<Lease> lease;
        try {
            lease = store.lease(clock.instant(), config.leaseTtl());
        } catch (RuntimeException e) {
            internalErrors.increment();
            limiter.release(Outcome.NEUTRAL);
            return false;
        }
        if (lease.isEmpty()) {
            limiter.release(Outcome.NEUTRAL);
            return false;
        }
        try {
            executor.execute(() -> process(lease.get()));
        } catch (RejectedExecutionException e) {
            limiter.release(Outcome.NEUTRAL);
            return false;
        }
        return true;
    }

    private void process(Lease lease) {
        Outcome outcome = Outcome.NEUTRAL;
        try {
            TemplateRef template = client.loadTemplateRef(lease.engagement());
            sink.record(lease.engagement(), template);
            if (!store.complete(lease)) {
                fenced.increment();
            }
            breaker.recordSuccess();
            succeeded.increment();
            outcome = Outcome.SUCCESS;
        } catch (DownstreamException e) {
            outcome = onDownstreamFailure(lease, e);
        } catch (RuntimeException e) {
            internalErrors.increment();
            retryOrDead(lease, "internal error: " + e);
        } finally {
            limiter.release(outcome);
        }
    }

    private Outcome onDownstreamFailure(Lease lease, DownstreamException e) {
        switch (e.kind()) {
            case THROTTLED -> {
                throttled.increment();
                var notBefore = clock.instant().plus(retryPolicy.delay(lease.attempt(), random, e.retryAfter()));
                guarded(() -> store.retryLater(lease, notBefore, e.getMessage(), false));
                return Outcome.THROTTLED;
            }
            case TRANSIENT -> {
                breaker.recordFailure();
                retryOrDead(lease, e.getMessage());
                return Outcome.NEUTRAL;
            }
            case PERMANENT -> {
                breaker.recordSuccess();
                dead.increment();
                guarded(() -> store.markDead(lease, e.getMessage()));
                return Outcome.NEUTRAL;
            }
        }
        return Outcome.NEUTRAL;
    }

    private void retryOrDead(Lease lease, String error) {
        if (retryPolicy.exhausted(lease.attempt())) {
            dead.increment();
            guarded(() -> store.markDead(lease, error));
            return;
        }
        retried.increment();
        var notBefore = clock.instant().plus(retryPolicy.delay(lease.attempt(), random, null));
        guarded(() -> store.retryLater(lease, notBefore, error, true));
    }

    private void guarded(Runnable storeCall) {
        try {
            storeCall.run();
        } catch (RuntimeException e) {
            internalErrors.increment();
        }
    }
}
