package com.caseware.fanout.configuration;

import com.caseware.fanout.event.PublishEvent;
import com.caseware.fanout.listener.PublishEventListener;
import com.caseware.fanout.model.EngagementRef;
import com.caseware.fanout.repository.InMemoryUnverifiedEngagementRepository;
import com.caseware.fanout.repository.WorkQueueRepository;
import com.caseware.fanout.resilience.AdaptiveConcurrencyLimiter;
import com.caseware.fanout.service.FanoutWorkerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "fanout.demo", name = "enabled", havingValue = "true")
public class DemoRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoRunner.class);

    private final FanoutProperties.Demo demo;
    private final InMemoryUnverifiedEngagementRepository directory;
    private final PublishEventListener listener;
    private final WorkQueueRepository queue;
    private final FanoutWorkerService worker;
    private final AdaptiveConcurrencyLimiter limiter;

    public DemoRunner(FanoutProperties properties, InMemoryUnverifiedEngagementRepository directory,
                      PublishEventListener listener, WorkQueueRepository queue, FanoutWorkerService worker,
                      AdaptiveConcurrencyLimiter limiter) {
        this.demo = properties.demo();
        this.directory = directory;
        this.listener = listener;
        this.queue = queue;
        this.worker = worker;
        this.limiter = limiter;
    }

    @Override
    public void run(ApplicationArguments args) throws InterruptedException {
        List<EngagementRef> engagements = new ArrayList<>();
        for (int i = 0; i < demo.engagements(); i++) {
            String firm = i < demo.engagements() * 7 / 10 ? "firm-big" : "firm-" + (i % demo.firms());
            engagements.add(new EngagementRef("eng-" + i, firm));
        }
        directory.seed(demo.templateId(), engagements);

        var event = new PublishEvent(UUID.randomUUID().toString(), demo.templateId(), demo.version());
        log.info("Publish {} {}: queued {} engagements", demo.templateId(), demo.version(),
                listener.onPublishEvent(event));
        log.info("Redelivered event queued {} more (idempotent)", listener.onPublishEvent(event));

        while (true) {
            var stats = queue.stats();
            log.info("queue={} workers={} limit={} inFlight={}", stats, worker.stats(), limiter.limit(),
                    limiter.inFlight());
            if (stats.done() + stats.dead() == engagements.size()) {
                break;
            }
            Thread.sleep(1000);
        }
        log.info("Demo finished. Press Ctrl+C to stop.");
    }
}
