package com.sailv.terraform.analysis.application.model;

import com.sailv.terraform.analysis.domain.model.AnalysisWarning;
import java.util.List;
import java.util.Set;

/**
 * 单个 Terraform 文件的解析结果。
 *
 * <p>一个 module 目录下可能有多个 `.tf` / `.tf.json` 文件，
 * 应用层会把这些单文件结果继续汇总。
 */
public record ParsedTerraformFile(
    Set<String> providerBlockNames,
    Set<String> requiredProviderNames,
    Set<DiscoveredTerraformAction> actions,
    List<DiscoveredModuleReference> moduleReferences,
    List<AnalysisWarning> warnings
) {

    public ParsedTerraformFile {
        providerBlockNames = providerBlockNames == null ? Set.of() : Set.copyOf(providerBlockNames);
        requiredProviderNames = requiredProviderNames == null ? Set.of() : Set.copyOf(requiredProviderNames);
        actions = actions == null ? Set.of() : Set.copyOf(actions);
        moduleReferences = moduleReferences == null ? List.of() : List.copyOf(moduleReferences);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
