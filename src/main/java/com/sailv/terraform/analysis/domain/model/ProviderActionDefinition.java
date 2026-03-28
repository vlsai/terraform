package com.sailv.terraform.analysis.domain.model;

import java.util.Objects;

public record ProviderActionDefinition(
    String providerName,
    String actionName,
    String resourceType,
    String providerType,
    String quotaType
) {

    public ProviderActionDefinition {
        providerName = requireText(providerName, "providerName");
        actionName = requireText(actionName, "actionName");
        resourceType = normalizeNullable(resourceType);
        providerType = normalizeNullable(providerType);
        quotaType = normalizeNullable(quotaType);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " cannot be null");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return trimmed;
    }

    private static String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
