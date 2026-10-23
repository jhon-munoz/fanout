package com.caseware.fanout.repository;

import com.caseware.fanout.model.EngagementRef;
import com.caseware.fanout.model.Lease;
import com.caseware.fanout.model.StoreStats;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

/**
 * Durable work queue with one item per engagement.
 *
 * <p>Contracts:
 * <ul>
 *   <li>{@code enqueue} is idempotent and coalescing: an engagement already pending, leased or done is not queued again.</li>
 *   <li>{@code lease} hands out items fairly across firms, only when their {@code notBefore} has passed,
 *       and reclaims leases that expired (crashed workers). Each lease counts as one attempt.</li>
 *   <li>Completion calls are fenced by the lease token and return {@code false} when the lease is no longer the owner.</li>
 * </ul>
 */
public interface WorkQueueRepository {

    int enqueue(Collection<EngagementRef> engagements);

    Optional<Lease> lease(Instant now, Duration ttl);

    boolean complete(Lease lease);

    boolean retryLater(Lease lease, Instant notBefore, String error, boolean countAttempt);

    boolean markDead(Lease lease, String error);

    StoreStats stats();
}
