package com.sailv.terraform.analysis.domain.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 配额资源识别配置。
 *
 * <p>当前库不会直接执行配额检查。
 * 这个配置只用于告诉分析过程：
 * 哪些 `resource_type` 需要进入 `t_mp_template_resource`，
 * 以及在预制表未返回 `quota_type` 时可使用哪个默认 `quota_type`。
 *
 * <p>`checkUrl` 仍然保留，是因为上游配置就是 `resource_type -> url`，
 * 后续系统如果需要基于同一份配置做实际配额校验，可以继续复用。
 */
@Getter
@ToString
@EqualsAndHashCode
@Accessors(fluent = true)
public final class QuotaCheckRule {
    private final String resourceType;
    private final String checkUrl;
    private final String quotaType;

    public QuotaCheckRule(String resourceType, String checkUrl, String quotaType) {
        this.resourceType = requireText(resourceType, "resourceType");
        this.checkUrl = requireText(checkUrl, "checkUrl");
        this.quotaType = normalizeNullable(quotaType);
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
