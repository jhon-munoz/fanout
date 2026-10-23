package com.caseware.fanout.service;

import com.caseware.fanout.model.EngagementRef;
import com.caseware.fanout.event.PublishEvent;
import com.caseware.fanout.repository.UnverifiedEngagementRepository;
import com.caseware.fanout.repository.UnverifiedEngagementRepository.Page;
import com.caseware.fanout.repository.InMemoryWorkQueueRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublishFanoutServiceTest {

    private static final PublishEvent EVENT = new PublishEvent("evt-1", "tmpl-1", "v5");

    private final InMemoryWorkQueueRepository store = new InMemoryWorkQueueRepository(3);
    private final List<EngagementRef> all = new ArrayList<>();

    PublishFanoutServiceTest() {
        for (int i = 0; i < 250; i++) {
            all.add(new EngagementRef("e" + i, "firm-" + (i % 3)));
        }
    }

    private UnverifiedEngagementRepository pagedDirectory(AtomicBoolean failOnSecondPage) {
        return (templateId, cursor, limit) -> {
            int from = cursor == null ? 0 : Integer.parseInt(cursor);
            if (from > 0 && failOnSecondPage.getAndSet(false)) {
                throw new IllegalStateException("directory unavailable");
            }
            int to = Math.min(from + limit, all.size());
            String next = to < all.size() ? String.valueOf(to) : null;
            return new Page(all.subList(from, to), next);
        };
    }

    @Test
    void queuesEveryPage() {
        var fanout = new PublishFanoutService(pagedDirectory(new AtomicBoolean()), store, 100);

        assertEquals(250, fanout.onPublish(EVENT));
        assertEquals(250, store.stats().pending());
    }

    @Test
    void redeliveredEventDoesNotDuplicateWork() {
        var fanout = new PublishFanoutService(pagedDirectory(new AtomicBoolean()), store, 100);

        fanout.onPublish(EVENT);
        assertEquals(0, fanout.onPublish(EVENT));
        assertEquals(250, store.stats().pending());
    }

    @Test
    void crashMidEnumerationIsRepairedByRedelivery() {
        var fanout = new PublishFanoutService(pagedDirectory(new AtomicBoolean(true)), store, 100);

        assertThrows(IllegalStateException.class, () -> fanout.onPublish(EVENT));
        assertEquals(100, store.stats().pending());

        assertEquals(150, fanout.onPublish(EVENT));
        assertEquals(250, store.stats().pending());
    }
}
