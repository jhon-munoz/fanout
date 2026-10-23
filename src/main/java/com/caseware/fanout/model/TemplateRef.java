package com.caseware.fanout.model;

import java.util.Objects;

public record TemplateRef(String templateId, String version) {

    public TemplateRef {
        Objects.requireNonNull(templateId, "templateId");
        Objects.requireNonNull(version, "version");
    }
}
