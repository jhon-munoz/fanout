package com.caseware.fanout.model;

import java.util.Objects;

public record EngagementRef(String engagementId, String firmId) {

    public EngagementRef {
        Objects.requireNonNull(engagementId, "engagementId");
        Objects.requireNonNull(firmId, "firmId");
    }
}
