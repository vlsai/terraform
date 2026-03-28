package com.sailv.terraform.analysis.domain.service;

import com.sailv.terraform.analysis.application.model.DiscoveredTemplateStructure;
import com.sailv.terraform.analysis.application.model.DiscoveredTerraformAction;
import com.sailv.terraform.analysis.domain.model.AnalysisWarning;
import com.sailv.terraform.analysis.domain.model.ProviderActionDefinition;
import com.sailv.terraform.analysis.domain.model.QuotaCheckRequest;
import com.sailv.terraform.analysis.domain.model.QuotaCheckRule;
import com.sailv.terraform.analysis.domain.model.TemplateAnalysisResult;
import com.sailv.terraform.analysis.domain.model.TemplateProvider;
import com.sailv.terraform.analysis.domain.model.TemplateQuotaResource;
import com.sailv.terraform.analysis.domain.port.ProviderActionQueryPort;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class TemplateAnalysisDomainService {

    private final ProviderActionQueryPort providerActionQueryPort;

    public TemplateAnalysisDomainService(ProviderActionQueryPort providerActionQueryPort) {
        this.providerActionQueryPort = providerActionQueryPort;
    }

    public TemplateAnalysisResult analyze(
        Long templateId,
        DiscoveredTemplateStructure discovered,
        Collection<QuotaCheckRule> quotaRules
    ) {
        Map<String, QuotaCheckRule> quotaRuleIndex = QuotaCheckRule.indexByResourceType(quotaRules);
        List<AnalysisWarning> warnings = new ArrayList<>(discovered.warnings());

        Map<ActionLookupKey, ProviderActionDefinition> definitions = loadDefinitions(discovered.actions(), warnings);

        Set<String> usedProviders = new LinkedHashSet<>(discovered.providerBlockNames());
        discovered.actions().stream()
            .map(DiscoveredTerraformAction::providerName)
            .forEach(usedProviders::add);

        List<TemplateProvider> providers = usedProviders.stream()
            .sorted()
            .map(providerName -> new TemplateProvider(templateId, providerName, resolveProviderType(providerName, definitions.values())))
            .toList();

        Set<TemplateQuotaResource> quotaResources = new LinkedHashSet<>();
        Set<QuotaCheckRequest> quotaChecks = new LinkedHashSet<>();

        discovered.actions().stream()
            .sorted(Comparator
                .comparing(DiscoveredTerraformAction::providerName)
                .thenComparing(DiscoveredTerraformAction::actionName))
            .forEach(action -> {
                ProviderActionDefinition definition = definitions.get(new ActionLookupKey(action.providerName(), action.actionName()));
                if (definition == null || definition.resourceType() == null) {
                    return;
                }
                QuotaCheckRule rule = quotaRuleIndex.get(definition.resourceType());
                if (rule == null) {
                    return;
                }
                String quotaType = definition.quotaType() != null ? definition.quotaType() : rule.quotaType();
                quotaResources.add(new TemplateQuotaResource(templateId, definition.resourceType(), quotaType));
                quotaChecks.add(new QuotaCheckRequest(
                    templateId,
                    definition.providerName(),
                    definition.actionName(),
                    definition.resourceType(),
                    quotaType,
                    rule.checkUrl()
                ));
            });

        return new TemplateAnalysisResult(
            templateId,
            providers,
            quotaResources.stream()
                .sorted(Comparator.comparing(TemplateQuotaResource::resourceType))
                .toList(),
            quotaChecks.stream()
                .sorted(Comparator
                    .comparing(QuotaCheckRequest::resourceType)
                    .thenComparing(QuotaCheckRequest::providerName)
                    .thenComparing(QuotaCheckRequest::actionName))
                .toList(),
            warnings.stream()
                .sorted(Comparator.comparing(AnalysisWarning::code).thenComparing(AnalysisWarning::source))
                .toList()
        );
    }

    private Map<ActionLookupKey, ProviderActionDefinition> loadDefinitions(
        Set<DiscoveredTerraformAction> actions,
        List<AnalysisWarning> warnings
    ) {
        Map<ActionLookupKey, ProviderActionDefinition> definitions = new LinkedHashMap<>();
        actions.stream()
            .sorted(Comparator
                .comparing(DiscoveredTerraformAction::providerName)
                .thenComparing(DiscoveredTerraformAction::actionName))
            .forEach(action -> {
                ActionLookupKey key = new ActionLookupKey(action.providerName(), action.actionName());
                if (definitions.containsKey(key)) {
                    return;
                }
                Optional<ProviderActionDefinition> definition = providerActionQueryPort.findByProviderNameAndActionName(
                    action.providerName(),
                    action.actionName()
                );
                if (definition.isPresent()) {
                    definitions.put(key, definition.get());
                    return;
                }
                warnings.add(new AnalysisWarning(
                    "ACTION_MAPPING_NOT_FOUND",
                    "No provider action mapping found for provider=" + action.providerName() + ", action=" + action.actionName(),
                    action.sourceFile()
                ));
            });
        return definitions;
    }

    private String resolveProviderType(String providerName, Collection<ProviderActionDefinition> definitions) {
        return definitions.stream()
            .filter(definition -> providerName.equals(definition.providerName()))
            .map(ProviderActionDefinition::providerType)
            .filter(value -> value != null && !value.isBlank())
            .findFirst()
            .orElse(null);
    }

    private record ActionLookupKey(String providerName, String actionName) {
    }
}
