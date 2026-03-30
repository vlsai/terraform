package com.sailv.terraform.analysis.domain.model;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 模板领域对象。
 *
 * <p>service 负责文件读取、zip entry 扫描、按目录汇总 locals；
 * 这个类只负责基于动作集合、预制表映射和配额规则生成最终入库结果。
 *
 * <p>这里有两个关键原则：
 * <ul>
 *     <li>`t_mp_provider_actions` 只负责把 `provider_name` 映射到 `resource_type` / `provider_type`</li>
 *     <li>`quota_type` 只来自配额规则配置，不再从预制表中读取</li>
 * </ul>
 */
@Getter
@Log4j2
public class TerraformTemplate {

    private static final String ECS_RESOURCE_TYPE = "ecs";
    private static final String EVS_RESOURCE_TYPE = "evs";

    private final List<TerraformAction> actions = new ArrayList<>();

    public void mergeActions(Collection<TerraformAction> mergedActions) {
        if (mergedActions == null || mergedActions.isEmpty()) {
            return;
        }
        actions.addAll(mergedActions);
    }

    public TemplateAnalysisResult analyze(
        String templateId,
        QuotaCheckRule quotaRules,
        Collection<ProviderActionDefinition> providerActionDefinitions
    ) {
        Map<String, QuotaCheckRule.CloudServiceRule> quotaRuleIndex = quotaRules == null
            ? Map.of()
            : quotaRules.indexByResourceType();
        Map<String, List<ProviderActionDefinition>> definitions = indexDefinitions(providerActionDefinitions);
        logMissingProviders(actions, definitions.keySet());

        Set<String> usedProviders = actions.stream()
            .map(TerraformAction::getProviderName)
            .filter(value -> value != null && !value.isBlank())
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        List<TemplateProvider> providers = usedProviders.stream()
            .sorted()
            .filter(definitions::containsKey)
            .map(providerName -> new TemplateProvider()
                .setTemplateId(templateId)
                .setProviderName(providerName)
                .setProviderType(resolveProviderType(providerName, definitions.get(providerName))))
            .toList();

        Map<QuotaResourceKey, Integer> quotaRequirements = new LinkedHashMap<>();
        QuotaCheckRule.CloudServiceRule evsRule = quotaRuleIndex.get(EVS_RESOURCE_TYPE);

        actions.stream()
            .sorted(Comparator.comparing(TerraformAction::getProviderName, Comparator.nullsLast(String::compareTo))
                .thenComparing(TerraformAction::getBlockName, Comparator.nullsLast(String::compareTo)))
            .forEach(action -> collectQuotaRequirements(action, definitions, quotaRuleIndex, evsRule, quotaRequirements));

        List<TemplateQuotaResource> quotaResources = quotaRequirements.entrySet().stream()
            .map(entry -> new TemplateQuotaResource()
                .setTemplateId(templateId)
                .setResourceType(entry.getKey().resourceType)
                .setQuotaType(entry.getKey().quotaType)
                .setQuotaRequirement(entry.getValue()))
            .sorted(Comparator.comparing(TemplateQuotaResource::getResourceType)
                .thenComparing(TemplateQuotaResource::getQuotaType))
            .toList();

        return new TemplateAnalysisResult()
            .setTemplateId(templateId)
            .setProviders(new ArrayList<>(providers))
            .setQuotaResources(new ArrayList<>(quotaResources));
    }

    private void collectQuotaRequirements(
        TerraformAction action,
        Map<String, List<ProviderActionDefinition>> definitions,
        Map<String, QuotaCheckRule.CloudServiceRule> quotaRuleIndex,
        QuotaCheckRule.CloudServiceRule evsRule,
        Map<QuotaResourceKey, Integer> quotaRequirements
    ) {
        if (action == null || action.getProviderName() == null || action.getProviderName().isBlank()) {
            return;
        }

        List<ProviderActionDefinition> providerDefinitions = definitions.get(action.getProviderName());
        if (providerDefinitions == null || providerDefinitions.isEmpty()) {
            return;
        }

        Set<String> mappedResourceTypes = providerDefinitions.stream()
            .map(ProviderActionDefinition::getResourceType)
            .map(QuotaCheckRule::normalizeResourceType)
            .filter(value -> value != null && !value.isBlank())
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        for (String mappedResourceType : mappedResourceTypes) {
            QuotaCheckRule.CloudServiceRule rule = quotaRuleIndex.get(mappedResourceType);
            if (rule == null) {
                continue;
            }

            if (ECS_RESOURCE_TYPE.equals(mappedResourceType)) {
                addEcsQuotas(action, rule, quotaRequirements);
                addEvsSystemDiskQuotasFromEcs(action, evsRule, quotaRequirements);
                continue;
            }
            if (EVS_RESOURCE_TYPE.equals(mappedResourceType)) {
                addEvsVolumeQuotas(action, rule, quotaRequirements);
                continue;
            }

            addGenericQuotas(action, rule, quotaRequirements);
        }
    }

    private void addGenericQuotas(
        TerraformAction action,
        QuotaCheckRule.CloudServiceRule rule,
        Map<QuotaResourceKey, Integer> quotaRequirements
    ) {
        List<String> matchedQuotaTypes = selectGenericQuotaTypes(action, rule);
        for (String quotaType : matchedQuotaTypes) {
            mergeQuotaRequirement(quotaRequirements, rule.getResourceType(), quotaType, action.getRequestedAmount());
        }
    }

    private List<String> selectGenericQuotaTypes(TerraformAction action, QuotaCheckRule.CloudServiceRule rule) {
        if (rule.getQuotaType() == null || rule.getQuotaType().isEmpty()) {
            return List.of();
        }
        if (rule.getQuotaType().size() == 1) {
            return rule.getQuotaType();
        }

        String normalizedProviderName = normalizeKey(action.getProviderName());
        List<String> matched = rule.getQuotaType().stream()
            .filter(quotaType -> quotaTypeMatchesProvider(normalizedProviderName, quotaType))
            .distinct()
            .toList();
        if (!matched.isEmpty()) {
            return matched;
        }

        log.warn("Could not determine quota type from provider mapping. providerName={}, resourceType={}, configuredQuotaTypes={}",
            action.getProviderName(), rule.getResourceType(), rule.getQuotaType());
        return List.of();
    }

    private boolean quotaTypeMatchesProvider(String normalizedProviderName, String quotaType) {
        String normalizedQuotaType = normalizeKey(quotaType);
        if (normalizedProviderName.contains(normalizedQuotaType)) {
            return true;
        }
        String discriminator = stripCommonQuotaSuffix(normalizedQuotaType);
        return discriminator != null
            && !discriminator.isBlank()
            && normalizedProviderName.contains(discriminator);
    }

    private String stripCommonQuotaSuffix(String normalizedQuotaType) {
        if (normalizedQuotaType == null) {
            return null;
        }
        String stripped = normalizedQuotaType
            .replace("instancecount", "")
            .replace("instance", "")
            .replace("cpucount", "cpu")
            .replace("ramcount", "ram")
            .replace("gigabytes", "gb")
            .replace("volumes", "volume");
        return stripped.isBlank() ? normalizedQuotaType : stripped;
    }

    private void addEcsQuotas(
        TerraformAction action,
        QuotaCheckRule.CloudServiceRule rule,
        Map<QuotaResourceKey, Integer> quotaRequirements
    ) {
        FlavorSpec flavorSpec = parseFlavorSpec(action.getFlavorId());

        for (String quotaType : rule.getQuotaType()) {
            String normalizedQuotaType = normalizeKey(quotaType);
            if (normalizedQuotaType.contains("cpu")) {
                if (flavorSpec == null) {
                    log.warn("Skip ECS cpu quota because flavorId could not be resolved. providerName={}",
                        action.getProviderName());
                    continue;
                }
                mergeQuotaRequirement(
                    quotaRequirements,
                    rule.getResourceType(),
                    quotaType,
                    action.getRequestedAmount() * flavorSpec.cpuCount
                );
                continue;
            }
            if (normalizedQuotaType.contains("ram") || normalizedQuotaType.contains("memory")) {
                if (flavorSpec == null) {
                    log.warn("Skip ECS ram quota because flavorId could not be resolved. providerName={}",
                        action.getProviderName());
                    continue;
                }
                mergeQuotaRequirement(
                    quotaRequirements,
                    rule.getResourceType(),
                    quotaType,
                    action.getRequestedAmount() * flavorSpec.memoryGiB
                );
                continue;
            }
            mergeQuotaRequirement(quotaRequirements, rule.getResourceType(), quotaType, action.getRequestedAmount());
        }
    }

    private void addEvsSystemDiskQuotasFromEcs(
        TerraformAction action,
        QuotaCheckRule.CloudServiceRule evsRule,
        Map<QuotaResourceKey, Integer> quotaRequirements
    ) {
        if (evsRule == null) {
            return;
        }

        for (String quotaType : evsRule.getQuotaType()) {
            String normalizedQuotaType = normalizeKey(quotaType);
            if (normalizedQuotaType.contains("gigabyte") || normalizedQuotaType.equals("gb")) {
                if (action.getSystemDiskSize() == null || action.getSystemDiskSize() <= 0) {
                    log.warn("Skip EVS gigabytes quota derived from ECS because systemDiskSize could not be resolved. providerName={}",
                        action.getProviderName());
                    continue;
                }
                mergeQuotaRequirement(
                    quotaRequirements,
                    evsRule.getResourceType(),
                    quotaType,
                    action.getRequestedAmount() * action.getSystemDiskSize()
                );
                continue;
            }
            mergeQuotaRequirement(quotaRequirements, evsRule.getResourceType(), quotaType, action.getRequestedAmount());
        }
    }

    private void addEvsVolumeQuotas(
        TerraformAction action,
        QuotaCheckRule.CloudServiceRule rule,
        Map<QuotaResourceKey, Integer> quotaRequirements
    ) {
        for (String quotaType : rule.getQuotaType()) {
            String normalizedQuotaType = normalizeKey(quotaType);
            if (normalizedQuotaType.contains("gigabyte") || normalizedQuotaType.equals("gb")) {
                if (action.getVolumeSize() == null || action.getVolumeSize() <= 0) {
                    log.warn("Skip EVS gigabytes quota because volumeSize could not be resolved. providerName={}",
                        action.getProviderName());
                    continue;
                }
                mergeQuotaRequirement(
                    quotaRequirements,
                    rule.getResourceType(),
                    quotaType,
                    action.getRequestedAmount() * action.getVolumeSize()
                );
                continue;
            }
            mergeQuotaRequirement(quotaRequirements, rule.getResourceType(), quotaType, action.getRequestedAmount());
        }
    }

    private FlavorSpec parseFlavorSpec(String flavorId) {
        if (flavorId == null || flavorId.isBlank()) {
            return null;
        }

        String[] segments = flavorId.trim().toLowerCase(Locale.ROOT).split("\\.");
        if (segments.length < 2) {
            return null;
        }

        String sizeToken = segments[segments.length - 2];
        String memoryRatioToken = segments[segments.length - 1];

        Integer cpuCount = parseCpuCount(sizeToken);
        Integer memoryRatio = parseInteger(memoryRatioToken);
        if (cpuCount == null || memoryRatio == null) {
            return null;
        }
        return new FlavorSpec(cpuCount, cpuCount * memoryRatio);
    }

    private Integer parseCpuCount(String sizeToken) {
        if (sizeToken == null || sizeToken.isBlank()) {
            return null;
        }
        String normalized = sizeToken.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "small", "medium" -> 1;
            case "large" -> 2;
            case "xlarge" -> 4;
            default -> {
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^(\\d+)xlarge$").matcher(normalized);
                if (!matcher.matches()) {
                    yield null;
                }
                yield Integer.parseInt(matcher.group(1)) * 4;
            }
        };
    }

    private Integer parseInteger(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(rawValue.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void mergeQuotaRequirement(
        Map<QuotaResourceKey, Integer> quotaRequirements,
        String resourceType,
        String quotaType,
        int amount
    ) {
        if (resourceType == null || resourceType.isBlank() || quotaType == null || quotaType.isBlank() || amount <= 0) {
            return;
        }
        quotaRequirements.merge(new QuotaResourceKey(resourceType, quotaType), amount, Integer::sum);
    }

    private Map<String, List<ProviderActionDefinition>> indexDefinitions(
        Collection<ProviderActionDefinition> providerActionDefinitions
    ) {
        Map<String, List<ProviderActionDefinition>> definitions = new LinkedHashMap<>();
        if (providerActionDefinitions == null) {
            return definitions;
        }
        for (ProviderActionDefinition definition : providerActionDefinitions) {
            if (definition == null || definition.getProviderName() == null || definition.getProviderName().isBlank()) {
                continue;
            }
            definitions.computeIfAbsent(definition.getProviderName(), ignored -> new ArrayList<>()).add(definition);
        }
        return definitions;
    }

    private void logMissingProviders(Collection<TerraformAction> usedActions, Set<String> knownProviders) {
        usedActions.stream()
            .map(TerraformAction::getProviderName)
            .filter(providerName -> providerName != null && !providerName.isBlank())
            .filter(providerName -> !knownProviders.contains(providerName))
            .sorted()
            .distinct()
            .forEach(providerName -> log.warn(
                "Provider was used in the template but was not found in the preset table. providerName={}",
                providerName
            ));
    }

    private String resolveProviderType(String providerName, Collection<ProviderActionDefinition> definitions) {
        if (definitions == null) {
            return null;
        }
        return definitions.stream()
            .filter(definition -> providerName.equals(definition.getProviderName()))
            .map(ProviderActionDefinition::getProviderType)
            .filter(value -> value != null && !value.isBlank())
            .findFirst()
            .orElse(null);
    }

    private String normalizeKey(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("_", "").replace("-", "").replace(" ", "").toLowerCase(Locale.ROOT);
    }

    private static final class FlavorSpec {
        private final int cpuCount;
        private final int memoryGiB;

        private FlavorSpec(int cpuCount, int memoryGiB) {
            this.cpuCount = cpuCount;
            this.memoryGiB = memoryGiB;
        }
    }

    private static final class QuotaResourceKey {
        private final String resourceType;
        private final String quotaType;

        private QuotaResourceKey(String resourceType, String quotaType) {
            this.resourceType = resourceType;
            this.quotaType = quotaType;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof QuotaResourceKey other)) {
                return false;
            }
            return java.util.Objects.equals(resourceType, other.resourceType)
                && java.util.Objects.equals(quotaType, other.quotaType);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(resourceType, quotaType);
        }
    }
}
