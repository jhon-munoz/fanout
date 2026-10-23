package com.caseware.fanout.repository;

import com.caseware.fanout.model.EngagementRef;

import java.util.List;

/**
 * Lists engagements of a template whose template reference the tracker index does not know yet.
 * Backed by the index, so already verified engagements are not returned.
 */
public interface UnverifiedEngagementRepository {

    Page listUnverified(String templateId, String cursor, int limit);

    record Page(List<EngagementRef> items, String nextCursor) {
    }
}
