package com.caseware.fanout.repository;

import com.caseware.fanout.model.EngagementRef;
import com.caseware.fanout.enums.ItemStatus;
import com.caseware.fanout.model.Lease;
import com.caseware.fanout.model.StoreStats;
import com.caseware.fanout.repository.WorkQueueRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class InMemoryWorkQueueRepository implements WorkQueueRepository {

    public record ItemSnapshot(ItemStatus status, int attempts, String lastError) {
    }

    private static final class Item {
        final EngagementRef engagement;
        ItemStatus status = ItemStatus.PENDING;
        int attempts;
        Instant notBefore = Instant.EPOCH;
        String token;
        Instant leaseExpiresAt;
        String lastError;

        Item(EngagementRef engagement) {
            this.engagement = engagement;
        }
    }

    private final int maxAttempts;
    private final Map<String, Item> items = new HashMap<>();
    private final Map<String, Deque<Item>> queuesByFirm = new HashMap<>();
    private final Deque<String> firmRotation = new ArrayDeque<>();
    private final Set<Item> leased = new HashSet<>();

    public InMemoryWorkQueueRepository(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    @Override
    public synchronized int enqueue(Collection<EngagementRef> engagements) {
        int queued = 0;
        for (EngagementRef engagement : engagements) {
            Item existing = items.get(engagement.engagementId());
            if (existing == null) {
                Item item = new Item(engagement);
                items.put(engagement.engagementId(), item);
                push(item, false);
                queued++;
            } else if (existing.status == ItemStatus.DEAD) {
                existing.status = ItemStatus.PENDING;
                existing.attempts = 0;
                existing.notBefore = Instant.EPOCH;
                existing.lastError = null;
                push(existing, false);
                queued++;
            }
        }
        return queued;
    }

    @Override
    public synchronized Optional<Lease> lease(Instant now, Duration ttl) {
        reclaimExpired(now);
        int firms = firmRotation.size();
        for (int i = 0; i < firms; i++) {
            String firm = firmRotation.pollFirst();
            Deque<Item> queue = queuesByFirm.get(firm);
            Item ready = removeFirstReady(queue, now);
            if (queue.isEmpty()) {
                queuesByFirm.remove(firm);
            } else {
                firmRotation.addLast(firm);
            }
            if (ready != null) {
                ready.status = ItemStatus.LEASED;
                ready.attempts++;
                ready.token = UUID.randomUUID().toString();
                ready.leaseExpiresAt = now.plus(ttl);
                leased.add(ready);
                return Optional.of(new Lease(ready.engagement, ready.token, ready.attempts, ready.leaseExpiresAt));
            }
        }
        return Optional.empty();
    }

    @Override
    public synchronized boolean complete(Lease lease) {
        Item item = owned(lease);
        if (item == null) {
            return false;
        }
        item.status = ItemStatus.DONE;
        item.token = null;
        item.lastError = null;
        leased.remove(item);
        return true;
    }

    @Override
    public synchronized boolean retryLater(Lease lease, Instant notBefore, String error, boolean countAttempt) {
        Item item = owned(lease);
        if (item == null) {
            return false;
        }
        leased.remove(item);
        item.status = ItemStatus.PENDING;
        item.token = null;
        item.notBefore = notBefore;
        item.lastError = error;
        if (!countAttempt) {
            item.attempts--;
        }
        push(item, false);
        return true;
    }

    @Override
    public synchronized boolean markDead(Lease lease, String error) {
        Item item = owned(lease);
        if (item == null) {
            return false;
        }
        leased.remove(item);
        item.status = ItemStatus.DEAD;
        item.token = null;
        item.lastError = error;
        return true;
    }

    @Override
    public synchronized StoreStats stats() {
        int pending = 0;
        int leasedCount = 0;
        int done = 0;
        int dead = 0;
        for (Item item : items.values()) {
            switch (item.status) {
                case PENDING -> pending++;
                case LEASED -> leasedCount++;
                case DONE -> done++;
                case DEAD -> dead++;
            }
        }
        return new StoreStats(pending, leasedCount, done, dead);
    }

    public synchronized Optional<ItemSnapshot> snapshot(String engagementId) {
        return Optional.ofNullable(items.get(engagementId))
                .map(item -> new ItemSnapshot(item.status, item.attempts, item.lastError));
    }

    private Item owned(Lease lease) {
        Item item = items.get(lease.engagement().engagementId());
        boolean owner = item != null && item.status == ItemStatus.LEASED && lease.token().equals(item.token);
        return owner ? item : null;
    }

    private void reclaimExpired(Instant now) {
        Iterator<Item> it = leased.iterator();
        while (it.hasNext()) {
            Item item = it.next();
            if (item.leaseExpiresAt.isAfter(now)) {
                continue;
            }
            it.remove();
            item.token = null;
            if (item.attempts >= maxAttempts) {
                item.status = ItemStatus.DEAD;
                item.lastError = "lease expired " + item.attempts + " times";
            } else {
                item.status = ItemStatus.PENDING;
                push(item, true);
            }
        }
    }

    private void push(Item item, boolean front) {
        Deque<Item> queue = queuesByFirm.computeIfAbsent(item.engagement.firmId(), firm -> {
            firmRotation.addLast(firm);
            return new ArrayDeque<>();
        });
        if (front) {
            queue.addFirst(item);
        } else {
            queue.addLast(item);
        }
    }

    private static Item removeFirstReady(Deque<Item> queue, Instant now) {
        Iterator<Item> it = queue.iterator();
        while (it.hasNext()) {
            Item item = it.next();
            if (!item.notBefore.isAfter(now)) {
                it.remove();
                return item;
            }
        }
        return null;
    }
}
