package com.caseware.fanout.repository;

import com.caseware.fanout.model.EngagementRef;
import com.caseware.fanout.model.TemplateRef;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryTemplateIndexRepository implements TemplateIndexRepository {

    private final Map<String, TemplateRef> index = new ConcurrentHashMap<>();

    @Override
    public void record(EngagementRef engagement, TemplateRef template) {
        index.put(engagement.engagementId(), template);
    }

    public boolean contains(String engagementId) {
        return index.containsKey(engagementId);
    }

    public Optional<TemplateRef> find(String engagementId) {
        return Optional.ofNullable(index.get(engagementId));
    }

    public int size() {
        return index.size();
    }
}
