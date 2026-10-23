package com.caseware.fanout.service;

import com.caseware.fanout.event.PublishEvent;
import com.caseware.fanout.repository.UnverifiedEngagementRepository;
import com.caseware.fanout.repository.UnverifiedEngagementRepository.Page;
import com.caseware.fanout.repository.WorkQueueRepository;

/**
 * Turns a publish event into work items. Safe under redelivery and crashes: enqueue is idempotent, so
 * a redelivered event simply re-enumerates from the start and queues only what is missing.
 */
public final class PublishFanoutService {

    private final UnverifiedEngagementRepository directory;
    private final WorkQueueRepository store;
    private final int pageSize;

    public PublishFanoutService(UnverifiedEngagementRepository directory, WorkQueueRepository store, int pageSize) {
        this.directory = directory;
        this.store = store;
        this.pageSize = pageSize;
    }

    public int onPublish(PublishEvent event) {
        int queued = 0;
        String cursor = null;
        do {
            Page page = directory.listUnverified(event.templateId(), cursor, pageSize);
            queued += store.enqueue(page.items());
            cursor = page.nextCursor();
        } while (cursor != null);
        return queued;
    }
}
