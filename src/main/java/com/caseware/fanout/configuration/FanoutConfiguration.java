package com.caseware.fanout.configuration;

import com.caseware.fanout.client.EngagementTemplateClient;
import com.caseware.fanout.client.SimulatedEngagementTemplateClient;
import com.caseware.fanout.repository.InMemoryTemplateIndexRepository;
import com.caseware.fanout.repository.InMemoryUnverifiedEngagementRepository;
import com.caseware.fanout.repository.InMemoryWorkQueueRepository;
import com.caseware.fanout.repository.TemplateIndexRepository;
import com.caseware.fanout.repository.UnverifiedEngagementRepository;
import com.caseware.fanout.repository.WorkQueueRepository;
import com.caseware.fanout.resilience.AdaptiveConcurrencyLimiter;
import com.caseware.fanout.resilience.CircuitBreaker;
import com.caseware.fanout.resilience.RetryPolicy;
import com.caseware.fanout.service.FanoutWorkerService;
import com.caseware.fanout.service.PublishFanoutService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.random.RandomGenerator;

/**
 * Wiring. The queue, the index, the directory and the downstream client are in-memory or simulated stand-ins:
 * replace those four beans with DynamoDB/SQS and the real engagement client to run against real systems.
 */
@Configuration
@EnableConfigurationProperties(FanoutProperties.class)
public class FanoutConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    RandomGenerator randomGenerator() {
        return RandomGenerator.getDefault();
    }

    @Bean
    WorkQueueRepository workQueueRepository(FanoutProperties properties) {
        return new InMemoryWorkQueueRepository(properties.retry().maxAttempts());
    }

    @Bean
    InMemoryTemplateIndexRepository templateIndexRepository() {
        return new InMemoryTemplateIndexRepository();
    }

    @Bean
    InMemoryUnverifiedEngagementRepository unverifiedEngagementRepository(InMemoryTemplateIndexRepository index) {
        return new InMemoryUnverifiedEngagementRepository(index);
    }

    @Bean
    EngagementTemplateClient engagementTemplateClient(FanoutProperties properties, RandomGenerator random) {
        return new SimulatedEngagementTemplateClient(properties.simulation(), random);
    }

    @Bean
    AdaptiveConcurrencyLimiter concurrencyLimiter(FanoutProperties properties, Clock clock) {
        var concurrency = properties.concurrency();
        return new AdaptiveConcurrencyLimiter(concurrency.min(), concurrency.initial(), concurrency.max(),
                concurrency.decreaseCooldown(), clock);
    }

    @Bean
    CircuitBreaker circuitBreaker(FanoutProperties properties, Clock clock) {
        return new CircuitBreaker(properties.breaker().failureThreshold(), properties.breaker().openFor(), clock);
    }

    @Bean
    RetryPolicy retryPolicy(FanoutProperties properties) {
        var retry = properties.retry();
        return new RetryPolicy(retry.maxAttempts(), retry.baseDelay(), retry.maxDelay());
    }

    @Bean
    PublishFanoutService publishFanoutService(UnverifiedEngagementRepository directory,
                                              WorkQueueRepository queue, FanoutProperties properties) {
        return new PublishFanoutService(directory, queue, properties.publish().pageSize());
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    FanoutWorkerService fanoutWorkerService(WorkQueueRepository queue, EngagementTemplateClient client,
                                            TemplateIndexRepository index, AdaptiveConcurrencyLimiter limiter,
                                            CircuitBreaker breaker, RetryPolicy retryPolicy,
                                            FanoutProperties properties, Clock clock, RandomGenerator random) {
        return new FanoutWorkerService(queue, client, index, limiter, breaker, retryPolicy,
                properties.workerConfig(), clock, random);
    }
}
