package com.caseware.fanout.event;

import java.util.Objects;

public record PublishEvent(String eventId, String templateId, String version) {

    public PublishEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(templateId, "templateId");
        Objects.requireNonNull(version, "version");
    }
}
