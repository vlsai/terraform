package com.sailv.terraform.analysis.application.model;

import java.util.Objects;

public record DiscoveredModuleReference(String rawSource, String sourceFile) {

    public DiscoveredModuleReference {
        rawSource = normalize(rawSource);
        sourceFile = normalize(sourceFile);
    }

    private static String normalize(String value) {
        Objects.requireNonNull(value, "value cannot be null");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("value cannot be blank");
        }
        return trimmed;
    }
}
