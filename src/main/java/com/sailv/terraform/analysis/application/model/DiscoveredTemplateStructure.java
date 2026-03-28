package com.sailv.terraform.analysis.application.model;

import com.sailv.terraform.analysis.domain.model.AnalysisWarning;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record DiscoveredTemplateStructure(
    Set<String> providerBlockNames,
    Set<String> requiredProviderNames,
    Set<DiscoveredTerraformAction> actions,
    List<AnalysisWarning> warnings
) {

    public DiscoveredTemplateStructure {
        providerBlockNames = providerBlockNames == null ? Set.of() : Set.copyOf(providerBlockNames);
        requiredProviderNames = requiredProviderNames == null ? Set.of() : Set.copyOf(requiredProviderNames);
        actions = actions == null ? Set.of() : Set.copyOf(actions);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Set<String> providerBlockNames = new LinkedHashSet<>();
        private final Set<String> requiredProviderNames = new LinkedHashSet<>();
        private final Set<DiscoveredTerraformAction> actions = new LinkedHashSet<>();
        private final List<AnalysisWarning> warnings = new java.util.ArrayList<>();

        public Builder addProviders(Set<String> names) {
            if (names != null) {
                providerBlockNames.addAll(names);
            }
            return this;
        }

        public Builder addRequiredProviders(Set<String> names) {
            if (names != null) {
                requiredProviderNames.addAll(names);
            }
            return this;
        }

        public Builder addActions(Set<DiscoveredTerraformAction> discoveredActions) {
            if (discoveredActions != null) {
                actions.addAll(discoveredActions);
            }
            return this;
        }

        public Builder addWarnings(List<AnalysisWarning> discoveredWarnings) {
            if (discoveredWarnings != null) {
                warnings.addAll(discoveredWarnings);
            }
            return this;
        }

        public DiscoveredTemplateStructure build() {
            return new DiscoveredTemplateStructure(providerBlockNames, requiredProviderNames, actions, warnings);
        }
    }
}
