package com.caseware.fanout.client;

import com.caseware.fanout.configuration.FanoutProperties.Simulation;
import com.caseware.fanout.exception.DownstreamException;
import com.caseware.fanout.exception.DownstreamException.Kind;
import com.caseware.fanout.model.EngagementRef;
import com.caseware.fanout.model.TemplateRef;

import java.time.Duration;
import java.util.random.RandomGenerator;

public final class SimulatedEngagementTemplateClient implements EngagementTemplateClient {

    private final Simulation simulation;
    private final RandomGenerator random;

    public SimulatedEngagementTemplateClient(Simulation simulation, RandomGenerator random) {
        this.simulation = simulation;
        this.random = random;
    }

    @Override
    public TemplateRef loadTemplateRef(EngagementRef engagement) throws DownstreamException {
        try {
            Thread.sleep(simulation.latency().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownstreamException(Kind.TRANSIENT, "interrupted", e, null);
        }
        double roll = random.nextDouble();
        double throttle = simulation.throttleRate();
        double transientFailure = throttle + simulation.transientFailureRate();
        double permanentFailure = transientFailure + simulation.permanentFailureRate();
        if (roll < throttle) {
            throw new DownstreamException(Kind.THROTTLED, "429 too many requests", null, Duration.ofSeconds(1));
        }
        if (roll < transientFailure) {
            throw new DownstreamException(Kind.TRANSIENT, "timeout loading " + engagement.engagementId());
        }
        if (roll < permanentFailure) {
            throw new DownstreamException(Kind.PERMANENT, "engagement " + engagement.engagementId() + " not found");
        }
        return new TemplateRef(simulation.templateId(), simulation.baseVersion());
    }
}
