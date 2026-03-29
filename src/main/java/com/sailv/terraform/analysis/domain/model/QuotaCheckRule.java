package com.sailv.terraform.analysis.domain.model;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 配额检查配置。
 *
 * <p>表示某个 resourceType 命中后需要检查配额，以及应当调用哪个 URL。
 */
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
        // 兼容最简单的调用方式：只有 resource_type -> url 的配置，没有 quota_type。
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
