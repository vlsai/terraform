package com.sailv.terraform.analysis.domain.model;

import java.util.Objects;

/**
 * 预制表 {@code t_mp_provider_actions} 的领域映射。
 *
 * <p>迁移到内网项目时，需要由上层持久化层把数据库查询结果转成这个对象。
 */
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
