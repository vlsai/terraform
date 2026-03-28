package com.sailv.terraform.analysis.application.model;

import com.sailv.terraform.analysis.domain.model.TerraformActionKind;
import java.util.Objects;

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
