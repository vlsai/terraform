package com.sailv.terraform.analysis.domain.model;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record QuotaCheckRule(String resourceType, String checkUrl, String quotaType) {

    public QuotaCheckRule {
        resourceType = requireText(resourceType, "resourceType");
        checkUrl = requireText(checkUrl, "checkUrl");
        quotaType = normalizeNullable(quotaType);
    }

    public static QuotaCheckRule of(String resourceType, String checkUrl) {
        return new QuotaCheckRule(resourceType, checkUrl, null);
    }

    public static Map<String, QuotaCheckRule> indexByResourceType(Collection<QuotaCheckRule> rules) {
        Map<String, QuotaCheckRule> indexed = new LinkedHashMap<>();
        if (rules == null) {
            return indexed;
        }
        for (QuotaCheckRule rule : rules) {
            if (rule != null) {
                indexed.put(rule.resourceType(), rule);
            }
        }
        return indexed;
    }

    public static Collection<QuotaCheckRule> fromMap(Map<String, String> resourceTypeToUrl) {
        Map<String, QuotaCheckRule> rules = new LinkedHashMap<>();
        if (resourceTypeToUrl == null) {
            return rules.values();
        }
        resourceTypeToUrl.forEach((resourceType, url) -> rules.put(resourceType, QuotaCheckRule.of(resourceType, url)));
        return rules.values();
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
