package com.caseware.fanout.repository;

import com.caseware.fanout.model.EngagementRef;
import com.caseware.fanout.enums.ItemStatus;
import com.caseware.fanout.model.Lease;
import com.caseware.fanout.testsupport.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryWorkQueueRepositoryTest {

    private static final Duration TTL = Duration.ofSeconds(1);

    private final TestClock clock = new TestClock();
    private final InMemoryWorkQueueRepository store = new InMemoryWorkQueueRepository(3);

    private static EngagementRef engagement(String id, String firm) {
        return new EngagementRef(id, firm);
    }

    @Test
    void enqueueIsIdempotentAndCoalescing() {
        var batch = List.of(engagement("e1", "f1"), engagement("e2", "f1"));

        assertEquals(2, store.enqueue(batch));
        assertEquals(0, store.enqueue(batch));
        assertEquals(2, store.stats().pending());

        Lease lease = store.lease(clock.instant(), TTL).orElseThrow();
        assertEquals(0, store.enqueue(List.of(lease.engagement())));
        store.complete(lease);
        assertEquals(0, store.enqueue(List.of(lease.engagement())));
        assertEquals(1, store.stats().done());
    }

    @Test
    void expiredLeaseIsReclaimedAndStaleHolderIsFencedOut() {
        store.enqueue(List.of(engagement("e1", "f1")));
        Lease first = store.lease(clock.instant(), TTL).orElseThrow();

        clock.advance(Duration.ofSeconds(2));
        Lease second = store.lease(clock.instant(), TTL).orElseThrow();

        assertEquals(2, second.attempt());
        assertFalse(store.complete(first));
        assertTrue(store.complete(second));
        assertEquals(ItemStatus.DONE, store.snapshot("e1").orElseThrow().status());
    }

    @Test
    void itemThatKeepsExpiringBecomesDead() {
        store.enqueue(List.of(engagement("e1", "f1")));
        for (int i = 0; i < 3; i++) {
            store.lease(clock.instant(), TTL).orElseThrow();
            clock.advance(Duration.ofSeconds(2));
        }

        assertTrue(store.lease(clock.instant(), TTL).isEmpty());
        assertEquals(ItemStatus.DEAD, store.snapshot("e1").orElseThrow().status());
    }

    @Test
    void leasesAreSharedFairlyAcrossFirms() {
        List<EngagementRef> big = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            big.add(engagement("big-" + i, "big"));
        }
        store.enqueue(big);
        store.enqueue(List.of(engagement("small-1", "small")));

        Lease first = store.lease(clock.instant(), TTL).orElseThrow();
        Lease second = store.lease(clock.instant(), TTL).orElseThrow();

        assertEquals("big", first.engagement().firmId());
        assertEquals("small", second.engagement().firmId());
    }

    @Test
    void retryLaterHonoursNotBeforeAndCanRefundTheAttempt() {
        store.enqueue(List.of(engagement("e1", "f1")));
        Lease lease = store.lease(clock.instant(), TTL).orElseThrow();
        assertTrue(store.retryLater(lease, clock.instant().plusSeconds(10), "throttled", false));

        assertTrue(store.lease(clock.instant(), TTL).isEmpty());
        clock.advance(Duration.ofSeconds(10));
        Lease retry = store.lease(clock.instant(), TTL).orElseThrow();
        assertEquals(1, retry.attempt());

        assertTrue(store.retryLater(retry, clock.instant(), "boom", true));
        assertEquals(2, store.lease(clock.instant(), TTL).orElseThrow().attempt());
    }

    @Test
    void deadItemIsReopenedByANewEnqueue() {
        store.enqueue(List.of(engagement("e1", "f1")));
        Lease lease = store.lease(clock.instant(), TTL).orElseThrow();
        store.markDead(lease, "gone");

        assertEquals(1, store.enqueue(List.of(engagement("e1", "f1"))));
        assertEquals(1, store.lease(clock.instant(), TTL).orElseThrow().attempt());
    }
}
