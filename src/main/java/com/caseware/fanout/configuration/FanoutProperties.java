package com.caseware.fanout.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "fanout")
public record FanoutProperties(
        Worker worker,
        Concurrency concurrency,
        Retry retry,
        Breaker breaker,
        Publish publish,
        Simulation simulation,
        Demo demo) {

    public record Worker(Duration leaseTtl, Duration pollInterval, Duration shutdownGrace) {
    }

    public record Concurrency(int min, int initial, int max, Duration decreaseCooldown) {
    }

    public record Retry(int maxAttempts, Duration baseDelay, Duration maxDelay) {
    }

    public record Breaker(int failureThreshold, Duration openFor) {
    }

    public record Publish(int pageSize) {
    }

    public record Simulation(Duration latency, double throttleRate, double transientFailureRate,
                             double permanentFailureRate, String templateId, String baseVersion) {
    }

    public record Demo(boolean enabled, int engagements, int firms, String templateId, String version) {
    }

    public WorkerConfig workerConfig() {
        return new WorkerConfig(worker.leaseTtl(), worker.pollInterval(), worker.shutdownGrace());
    }
}
