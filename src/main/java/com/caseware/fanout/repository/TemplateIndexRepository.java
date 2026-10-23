package com.caseware.fanout.repository;

import com.caseware.fanout.model.EngagementRef;
import com.caseware.fanout.model.TemplateRef;

/**
 * Writes the verified template reference into the tracker index.
 *
 * <p>Contract: must be idempotent. The worker is at-least-once, so the same engagement can be recorded twice
 * (a crash between {@code record} and the store's {@code complete}, or an expired lease).
 */
public interface TemplateIndexRepository {

    void record(EngagementRef engagement, TemplateRef template);
}
