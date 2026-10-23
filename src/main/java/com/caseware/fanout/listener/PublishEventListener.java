package com.caseware.fanout.listener;

import com.caseware.fanout.event.PublishEvent;
import com.caseware.fanout.service.PublishFanoutService;
import org.springframework.stereotype.Component;

/**
 * Entry point for template-publish events. In production an SQS or EventBridge consumer calls this method;
 * the event may be delivered more than once and the service is idempotent.
 */
@Component
public class PublishEventListener {

    private final PublishFanoutService publishFanoutService;

    public PublishEventListener(PublishFanoutService publishFanoutService) {
        this.publishFanoutService = publishFanoutService;
    }

    public int onPublishEvent(PublishEvent event) {
        return publishFanoutService.onPublish(event);
    }
}
