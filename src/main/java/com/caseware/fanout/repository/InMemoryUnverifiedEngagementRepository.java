package com.caseware.fanout.repository;

import com.caseware.fanout.model.EngagementRef;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryUnverifiedEngagementRepository implements UnverifiedEngagementRepository {

    private final InMemoryTemplateIndexRepository index;
    private final Map<String, List<EngagementRef>> engagementsByTemplate = new ConcurrentHashMap<>();

    public InMemoryUnverifiedEngagementRepository(InMemoryTemplateIndexRepository index) {
        this.index = index;
    }

    public void seed(String templateId, List<EngagementRef> engagements) {
        engagementsByTemplate.put(templateId, List.copyOf(engagements));
    }

    @Override
    public Page listUnverified(String templateId, String cursor, int limit) {
        List<EngagementRef> all = engagementsByTemplate.getOrDefault(templateId, List.of());
        int from = cursor == null ? 0 : Integer.parseInt(cursor);
        int to = Math.min(from + limit, all.size());
        List<EngagementRef> unverified = all.subList(from, to).stream()
                .filter(engagement -> !index.contains(engagement.engagementId()))
                .toList();
        return new Page(unverified, to < all.size() ? String.valueOf(to) : null);
    }
}
