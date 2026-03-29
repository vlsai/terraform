package com.sailv.terraform.analysis.application.model;

import com.sailv.terraform.analysis.domain.model.TerraformActionKind;
import java.util.Objects;

/**
 * 解析阶段发现的 Terraform 动作。
 *
 * <p>例如：
 * {@code resource "aws_instance" "web" {}} 会被解析成 providerName=aws, actionName=aws_instance。
 * 这里不直接包含 providerType / resourceType，因为这些值依赖预制表查询。
 */
public record DiscoveredTerraformAction(
    String providerName,
    String actionName,
    TerraformActionKind kind,
    String sourceFile
) {

    public DiscoveredTerraformAction {
        providerName = requireText(providerName, "providerName");
        actionName = requireText(actionName, "actionName");
        kind = Objects.requireNonNull(kind, "kind cannot be null");
        sourceFile = requireText(sourceFile, "sourceFile");
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
