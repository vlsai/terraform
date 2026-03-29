package com.sailv.terraform.analysis.domain.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Objects;

/**
 * 预制表 {@code t_mp_provider_actions} 的领域映射。
 *
 * <p>迁移到内网项目时，需要由上层持久化层把数据库查询结果转成这个对象。
 */
@Getter
@ToString
@EqualsAndHashCode
@Accessors(fluent = true)
public final class ProviderActionDefinition {
    private final String providerName;
    private final String actionName;
    private final String resourceType;
    private final String providerType;
    private final String quotaType;

    public ProviderActionDefinition(String providerName, String actionName, String resourceType, String providerType,
                                    String quotaType) {
        this.providerName = requireText(providerName, "providerName");
        this.actionName = requireText(actionName, "actionName");
        this.resourceType = normalizeNullable(resourceType);
        this.providerType = normalizeNullable(providerType);
        this.quotaType = normalizeNullable(quotaType);
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
