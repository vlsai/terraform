package com.sailv.terraform.analysis.domain.model;

import com.sailv.terraform.analysis.infrastructure.parser.TerraformFileParser;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Collection;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * 模板领域聚合。
 *
 * <p>这个类承载领域相关逻辑：
 * <ul>
 *     <li>收集模板里真正使用到的 Terraform 类型</li>
 *     <li>根据 gateway 查询结果补全 providerType / resourceType</li>
 *     <li>按 quota 规则生成最终结果</li>
 * </ul>
 *
 * <p>这样 service 层只负责编排文件处理、解压、递归 module 等“与领域无关”的逻辑。
 */
public class TerraformTemplateDomain {

    private static final Logger LOGGER = Logger.getLogger(TerraformTemplateDomain.class.getName());

    private final Set<String> providerBlockNames = new LinkedHashSet<>();
    private final List<TerraformAction> actions = new ArrayList<>();

    public void merge(TerraformFileParser.ParseResult parseResult) {
        providerBlockNames.addAll(parseResult.providerBlockNames());
        actions.addAll(parseResult.actions());
    }

    public List<TerraformAction> actions() {
        return List.copyOf(actions);
    }

    public TemplateAnalysisResult analyze(
        String templateId,
        Collection<QuotaCheckRule> quotaRules,
        Collection<ProviderActionDefinition> providerActionDefinitions
    ) {
        Map<String, QuotaCheckRule> quotaRuleIndex = QuotaCheckRule.indexByResourceType(quotaRules);
        Map<ActionLookupKey, ProviderActionDefinition> definitions = indexDefinitions(providerActionDefinitions);
        logMissingDefinitions(definitions);

        // t_mp_template_providers 存的不是顶层 provider block（例如 huaweicloud），
        // 而是模板实际使用到的 Terraform 类型（例如 huaweicloud_cce_node）。
        Set<String> usedProviders = actions.stream()
            .map(TerraformAction::providerName)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> knownProviders = definitions.values().stream()
            .map(ProviderActionDefinition::providerName)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        List<TemplateProvider> providers = usedProviders.stream()
            .sorted()
            .filter(knownProviders::contains)
            .map(providerName -> new TemplateProvider(templateId, providerName, resolveProviderType(providerName, definitions.values())))
            .toList();

        logMissingProviders(usedProviders, knownProviders);
        Map<QuotaResourceKey, Integer> quotaRequirements = new LinkedHashMap<>();

        actions.stream()
            .sorted(Comparator.comparing(TerraformAction::providerName)
                .thenComparing(TerraformAction::actionName)
                .thenComparing(TerraformAction::blockName))
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
                if (quotaType == null || quotaType.isBlank()) {
                    return;
                }
                QuotaResourceKey key = new QuotaResourceKey(definition.resourceType(), quotaType);
                quotaRequirements.merge(key, action.requestedAmount(), Integer::sum);
            });

        return new TemplateAnalysisResult(
            templateId,
            providers,
            quotaRequirements.entrySet().stream()
                .map(entry -> new TemplateQuotaResource(
                    templateId,
                    entry.getKey().resourceType(),
                    entry.getKey().quotaType(),
                    entry.getValue()
                ))
                .sorted(Comparator.comparing(TemplateQuotaResource::resourceType)
                    .thenComparing(TemplateQuotaResource::quotaType))
                .toList()
        );
    }

    private Map<ActionLookupKey, ProviderActionDefinition> indexDefinitions(
        Collection<ProviderActionDefinition> providerActionDefinitions
    ) {
        Map<ActionLookupKey, ProviderActionDefinition> definitions = new LinkedHashMap<>();
        if (providerActionDefinitions == null) {
            return definitions;
        }
        providerActionDefinitions.forEach(definition -> {
            if (definition == null) {
                return;
            }
            definitions.putIfAbsent(new ActionLookupKey(definition.providerName(), definition.actionName()), definition);
        });
        return definitions;
    }

    private void logMissingDefinitions(Map<ActionLookupKey, ProviderActionDefinition> definitions) {
        actions.stream()
            .sorted(Comparator.comparing(TerraformAction::providerName).thenComparing(TerraformAction::actionName))
            .forEach(action -> {
                ActionLookupKey key = new ActionLookupKey(action.providerName(), action.actionName());
                if (!definitions.containsKey(key)) {
                    LOGGER.warning(() -> "[ACTION_MAPPING_NOT_FOUND] No provider action mapping found for provider="
                        + action.providerName() + ", action=" + action.actionName());
                }
            });
    }

    private void logMissingProviders(Set<String> usedProviders, Set<String> knownProviders) {
        usedProviders.stream()
            .filter(providerName -> !knownProviders.contains(providerName))
            .sorted()
            .forEach(providerName -> LOGGER.warning(() ->
                "[PROVIDER_MAPPING_NOT_FOUND] Provider was used in template but not found in preset table: " + providerName));
    }

    private String resolveProviderType(String providerName, Collection<ProviderActionDefinition> definitions) {
        return definitions.stream()
            .filter(definition -> providerName.equals(definition.providerName()))
            .map(ProviderActionDefinition::providerType)
            .filter(value -> value != null && !value.isBlank())
            .findFirst()
            .orElse(null);
    }

    @Getter
    @ToString
    @EqualsAndHashCode
    @Accessors(fluent = true)
    private static final class ActionLookupKey {
        private final String providerName;
        private final String actionName;

        private ActionLookupKey(String providerName, String actionName) {
            this.providerName = providerName;
            this.actionName = actionName;
        }
    }

    @Getter
    @ToString
    @EqualsAndHashCode
    @Accessors(fluent = true)
    private static final class QuotaResourceKey {
        private final String resourceType;
        private final String quotaType;

        private QuotaResourceKey(String resourceType, String quotaType) {
            this.resourceType = resourceType;
            this.quotaType = quotaType;
        }
    }
}
