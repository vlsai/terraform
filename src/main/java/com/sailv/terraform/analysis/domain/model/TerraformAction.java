package com.sailv.terraform.analysis.domain.model;

import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Objects;

/**
 * 领域动作实体。
 *
 * <p>表示模板中实际发现的一条 Terraform resource / data 定义。
 * 它是 service 和 gateway 之间传递领域信息时用到的最小抽象。
 */
@Getter
@ToString
@lombok.EqualsAndHashCode
@Accessors(fluent = true)
public final class TerraformAction {
    private final String providerName;
    private final String actionName;
    private final String blockName;
    private final Kind kind;
    private final int requestedAmount;

    public TerraformAction(String providerName, String actionName, Kind kind) {
        this(providerName, actionName, actionName, kind, 1);
    }

    public TerraformAction(
        String providerName,
        String actionName,
        String blockName,
        Kind kind,
        int requestedAmount
    ) {
        this.providerName = requireText(providerName, "providerName");
        this.actionName = requireText(actionName, "actionName");
        this.blockName = requireText(blockName, "blockName");
        this.kind = Objects.requireNonNull(kind, "kind cannot be null");
        if (requestedAmount < 0) {
            throw new IllegalArgumentException("requestedAmount cannot be negative");
        }
        this.requestedAmount = requestedAmount;
    }

    public enum Kind {
        RESOURCE,
        DATA_SOURCE
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " cannot be null");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return trimmed;
    }
}
