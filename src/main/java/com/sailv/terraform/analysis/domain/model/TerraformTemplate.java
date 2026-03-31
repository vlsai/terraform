package com.sailv.terraform.analysis.domain.model;

import com.sailv.terraform.analysis.application.parser.TerraformFileParser;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.stream.Collectors;

/**
 * 模板领域对象。
 *
 * <p>提供两个主要能力：
 * <ul>
 *   <li>解析：在构造对象时，自动处理 zip 或单文件，提取局部变量并求值 Terraform 动作。</li>
 *   <li>配额聚合：在调用 extractQuotas() 时，基于预制表映射和配额规则生成入库对象。</li>
 * </ul>
 */
@Getter
@Log4j2
public class TerraformTemplate {

    private static final String ECS_RESOURCE_TYPE = "ecs";
    private static final String EVS_RESOURCE_TYPE = "evs";

    private final String fileId;
    
    // Key 格式为 sourceName:providerType:providerName:blockName，避免 zip 中不同目录下的同名 block 互相覆盖
    private final Map<String, TerraformAction> actions = new LinkedHashMap<>();

    public TerraformTemplate(
        String fileId,
        InputStream inputStream,
        String fileName,
        List<TerraformFileParser> parsers
    ) throws IOException {
        this.fileId = fileId;
        if (isZipFile(fileName)) {
            parseZip(inputStream, parsers);
        } else if (supportsByAnyParser(fileName, parsers)) {
            parseSingleFile(inputStream, fileName, parsers);
        } else {
            throw new IllegalArgumentException("Unsupported template source: " + fileName);
        }
    }

    // ==========================================
    // 1. 解析模版数据 (Parse 逻辑)
    // ==========================================

    private String buildActionKey(TerraformAction action, String sourceName) {
        if (action == null || action.getProviderName() == null || action.getBlockName() == null) {
            return null;
        }
        String normalizedSourceName = sourceName == null ? "" : sourceName;
        return normalizedSourceName + ":" + action.getProviderType() + ":" + action.getProviderName() + ":" + action.getBlockName();
    }

    private void addAction(TerraformAction action, String sourceName) {
        String key = buildActionKey(action, sourceName);
        if (key == null) {
            return;
        }
        actions.put(key, action);
    }

    private void parseZip(InputStream inputStream, List<TerraformFileParser> parsers) throws IOException {
        Map<String, ModuleAggregation> moduleAggregations = new LinkedHashMap<>();
        try (ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }

                String entryName = normalizeArchivePath(entry.getName());
                if (!supportsByAnyParser(entryName, parsers)) {
                    continue;
                }

                byte[] content = zipInputStream.readAllBytes();
                TerraformFileParser parser = parserFor(entryName, parsers);
                TerraformFileParser.ParseResult parseResult = parser.parse(new ByteArrayInputStream(content), entryName);

                moduleAggregations.computeIfAbsent(parentDirectory(entryName), ignored -> new ModuleAggregation())
                    .merge(parseResult, entryName);
            }
        }

        moduleAggregations.forEach((ignored, moduleAggregation) ->
            moduleAggregation.resolveActions().forEach(actionWithSource ->
                addAction(actionWithSource.action, actionWithSource.sourceName))
        );
    }

    private void parseSingleFile(InputStream inputStream, String fileName, List<TerraformFileParser> parsers) throws IOException {
        TerraformFileParser parser = parserFor(fileName, parsers);
        TerraformFileParser.ParseResult parseResult = parser.parse(inputStream, fileName);
        ModuleAggregation moduleAggregation = new ModuleAggregation();
        moduleAggregation.merge(parseResult, fileName);

        moduleAggregation.resolveActions().forEach(actionWithSource ->
            addAction(actionWithSource.action, actionWithSource.sourceName));
    }

    private static TerraformFileParser parserFor(String fileName, List<TerraformFileParser> parsers) {
        return parsers.stream()
            .filter(parser -> parser.supports(fileName))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No parser available for file " + fileName));
    }

    private static boolean isZipFile(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    private static boolean supportsByAnyParser(String fileName, List<TerraformFileParser> parsers) {
        return parsers.stream().anyMatch(parser -> parser.supports(fileName));
    }

    private static String parentDirectory(String fileName) {
        int lastSlash = fileName.lastIndexOf('/');
        if (lastSlash < 0) {
            return "";
        }
        return fileName.substring(0, lastSlash);
    }

    private static String normalizeArchivePath(String rawEntryName) {
        String normalized = rawEntryName == null ? "" : rawEntryName.replace('\\', '/').trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        List<String> segments = new ArrayList<>();
        for (String segment : normalized.split("/")) {
            if (segment.isBlank() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (!segments.isEmpty()) {
                    segments.removeLast();
                }
                continue;
            }
            segments.add(segment);
        }
        return String.join("/", segments);
    }

    // ==========================================
    // 2. 生成配额 (Quota Generation 逻辑)
    // ==========================================

    public TemplateAnalysisResult extractQuotas(
        QuotaCheckRule quotaRules,
        Map<String, List<ProviderAction>> providerActionsByProviderName
    ) {
        Map<String, List<ProviderAction>> providerActionIndex = providerActionsByProviderName == null
            ? Map.of()
            : providerActionsByProviderName;
        Map<String, QuotaCheckRule.CloudServiceRule> quotaRuleIndex = quotaRules == null
            ? Map.of()
            : quotaRules.indexByResourceType();
        Map<String, ProviderType> usedProviderTypes = collectUsedProviderTypes();

        List<TemplateProvider> templateProvidersList = new ArrayList<>();
        for (Map.Entry<String, ProviderType> usedProvider : usedProviderTypes.entrySet()) {
            String providerName = usedProvider.getKey();
            List<ProviderAction> providerRules = providerActionIndex.get(providerName);
            if (providerRules == null || providerRules.isEmpty()) {
                log.warn("Provider was used in the template but was not found in the preset table. providerName={}",
                    providerName);
                continue;
            }

            ProviderType resolvedProviderType = resolveProviderType(
                usedProvider.getValue(),
                providerRules
            );
            if (resolvedProviderType == null) {
                continue;
            }

            templateProvidersList.add(new TemplateProvider()
                .setTemplateId(fileId)
                .setProviderName(providerName)
                .setProviderType(resolvedProviderType.getDbValue()));
        }

        Map<QuotaResourceKey, Integer> quotaRequirements = new LinkedHashMap<>();
        QuotaCheckRule.CloudServiceRule evsRule = quotaRuleIndex.get(EVS_RESOURCE_TYPE);

        for (TerraformAction action : actions.values()) {
            if (action == null || action.getProviderType() != ProviderType.RESOURCE) {
                continue;
            }
            collectQuotaRequirements(action, providerActionIndex, quotaRuleIndex, evsRule, quotaRequirements);
        }

        List<TemplateQuotaResource> quotaResourcesList = quotaRequirements.entrySet().stream()
            .map(entry -> new TemplateQuotaResource()
                .setTemplateId(fileId)
                .setResourceType(entry.getKey().resourceType)
                .setQuotaType(entry.getKey().quotaType)
                .setQuotaRequirement(entry.getValue()))
            .toList();

        return new TemplateAnalysisResult()
            .setTemplateId(fileId)
            .setProviders(templateProvidersList)
            .setQuotaResources(new ArrayList<>(quotaResourcesList));
    }

    private Map<String, ProviderType> collectUsedProviderTypes() {
        Map<String, ProviderType> usedProviderTypes = new LinkedHashMap<>();
        for (TerraformAction action : actions.values()) {
            if (action == null || action.getProviderName() == null || action.getProviderName().isBlank()) {
                continue;
            }
            usedProviderTypes.merge(
                action.getProviderName(),
                action.getProviderType(),
                this::mergeProviderTypes
            );
        }
        return usedProviderTypes;
    }

    private ProviderType mergeProviderTypes(
        ProviderType current,
        ProviderType next
    ) {
        if (current == ProviderType.RESOURCE || next == ProviderType.RESOURCE) {
            return ProviderType.RESOURCE;
        }
        return current != null ? current : next;
    }

    private ProviderType resolveProviderType(
        ProviderType usedProviderType,
        List<ProviderAction> providerRules
    ) {
        if (providerRules == null || providerRules.isEmpty()) {
            return null;
        }

        ProviderType expectedType = usedProviderType == ProviderType.DATA
            ? ProviderType.DATA
            : ProviderType.RESOURCE;

        boolean hasExpectedType = providerRules.stream()
            .map(ProviderAction::getProviderType)
            .anyMatch(expectedType::equals);
        if (hasExpectedType) {
            return expectedType;
        }

        boolean hasResource = providerRules.stream()
            .map(ProviderAction::getProviderType)
            .anyMatch(ProviderType.RESOURCE::equals);
        if (hasResource) {
            return ProviderType.RESOURCE;
        }

        boolean hasData = providerRules.stream()
            .map(ProviderAction::getProviderType)
            .anyMatch(ProviderType.DATA::equals);
        return hasData ? ProviderType.DATA : null;
    }

    private void collectQuotaRequirements(
        TerraformAction action,
        Map<String, List<ProviderAction>> definitions,
        Map<String, QuotaCheckRule.CloudServiceRule> quotaRuleIndex,
        QuotaCheckRule.CloudServiceRule evsRule,
        Map<QuotaResourceKey, Integer> quotaRequirements
    ) {
        if (action == null || action.getProviderName() == null || action.getProviderName().isBlank()) {
            return;
        }

        List<ProviderAction> providerDefinitions = definitions.get(action.getProviderName());
        if (providerDefinitions == null || providerDefinitions.isEmpty()) {
            return;
        }

        Set<String> mappedResourceTypes = providerDefinitions.stream()
            .filter(pa -> pa.getProviderType() == ProviderType.RESOURCE)
            .map(ProviderAction::getResourceType)
            .map(QuotaCheckRule::normalizeResourceType)
            .filter(value -> value != null && !value.isBlank())
            .collect(Collectors.toCollection(LinkedHashSet::new));

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
                    log.debug("Skip ECS cpu quota because flavorId could not be resolved. providerName={}",
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
                    log.debug("Skip ECS ram quota because flavorId could not be resolved. providerName={}",
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
                    log.debug("Skip EVS gigabytes quota derived from ECS because systemDiskSize could not be resolved. providerName={}",
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
                    log.debug("Skip EVS gigabytes quota because volumeSize could not be resolved. providerName={}",
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

    // ==========================================
    // 3. 内部解析辅助类 (原位于 Service 中)
    // ==========================================

    private static class ModuleAggregation {
        private final Map<String, String> localValues = new LinkedHashMap<>();
        private final List<ActionWithSource> gatheredActions = new ArrayList<>();

        private void merge(TerraformFileParser.ParseResult parseResult, String sourceName) {
            if (parseResult == null) {
                return;
            }
            if (parseResult.getLocalValues() != null) {
                parseResult.getLocalValues().forEach(localValues::put);
            }
            if (parseResult.getActions() != null) {
                parseResult.getActions().forEach(action -> gatheredActions.add(new ActionWithSource(action, sourceName)));
            }
        }

        private List<ActionWithSource> resolveActions() {
            List<ActionWithSource> resolved = new ArrayList<>();
            for (ActionWithSource actionWithSource : gatheredActions) {
                TerraformAction action = actionWithSource.action;
                if (action == null) {
                    continue;
                }

                if (action.getProviderType() == ProviderType.DATA) {
                    action.setRequestedAmount(1);
                    action.setRequestedAmountExpression(null);
                    resolved.add(actionWithSource);
                    continue;
                }

                int requestedAmount = resolveRequestedAmount(
                    action.getRequestedAmountExpression(),
                    localValues,
                    actionWithSource.sourceName
                );
                if (requestedAmount <= 0) {
                    continue;
                }
                action.setRequestedAmount(requestedAmount);
                action.setFlavorId(resolveStringExpression(
                    action.getFlavorIdExpression(),
                    localValues,
                    actionWithSource.sourceName,
                    "flavor_id",
                    new LinkedHashSet<>()
                ));
                action.setSystemDiskSize(resolveIntegerExpression(
                    action.getSystemDiskSizeExpression(),
                    localValues,
                    actionWithSource.sourceName,
                    "system_disk_size"
                ));
                action.setVolumeSize(resolveIntegerExpression(
                    action.getVolumeSizeExpression(),
                    localValues,
                    actionWithSource.sourceName,
                    "size"
                ));
                resolved.add(actionWithSource);
            }
            return resolved;
        }
    }

    private static final class ActionWithSource {
        private final TerraformAction action;
        private final String sourceName;

        private ActionWithSource(TerraformAction action, String sourceName) {
            this.action = action;
            this.sourceName = sourceName;
        }
    }

    private static int resolveRequestedAmount(String expression, Map<String, String> localValues, String sourceName) {
        Integer resolved = resolveIntegerExpression(expression, localValues, sourceName, "count");
        return resolved == null ? 1 : Math.max(resolved, 0);
    }

    private static Integer resolveIntegerExpression(
        String expression,
        Map<String, String> localValues,
        String sourceName,
        String fieldName
    ) {
        String resolvedValue = resolveStringExpression(expression, localValues, sourceName, fieldName, new LinkedHashSet<>());
        if (resolvedValue == null) {
            return null;
        }
        Integer literal = parseLiteralIntegerHelper(resolvedValue);
        if (literal != null) {
            return literal;
        }
        log.debug("Unsupported integer expression. field={}, expression={}, source={}",
            fieldName, expression, sourceName);
        return null;
    }

    private static String resolveStringExpression(
        String expression,
        Map<String, String> localValues,
        String sourceName,
        String fieldName,
        LinkedHashSet<String> visitedLocals
    ) {
        String normalizedExpression = normalizeExpression(expression);
        if (normalizedExpression == null) {
            return null;
        }

        if (normalizedExpression.startsWith("local.")) {
            String localName = normalizedExpression.substring("local.".length());
            if (!visitedLocals.add(localName)) {
                log.debug("Detected local reference cycle. localName={}, source={}",
                    localName, sourceName);
                return null;
            }

            String localValue = localValues.get(localName);
            if (localValue == null) {
                log.debug("Local value not found while resolving expression. field={}, expression={}, source={}",
                    fieldName, normalizedExpression, sourceName);
                return null;
            }
            return resolveStringExpression(localValue, localValues, sourceName, fieldName, visitedLocals);
        }

        if (looksLikeUnsupportedExpression(normalizedExpression)) {
            log.debug("Unsupported expression. field={}, expression={}, source={}",
                fieldName,
                normalizedExpression,
                sourceName);
            return null;
        }

        return normalizedExpression;
    }

    private static boolean looksLikeUnsupportedExpression(String expression) {
        String normalized = expression == null ? null : expression.trim();
        if (normalized == null || normalized.isEmpty()) {
            return false;
        }
        return normalized.startsWith("var.")
            || normalized.startsWith("data.")
            || normalized.contains("?")
            || normalized.contains("(")
            || normalized.contains(")")
            || normalized.contains("[")
            || normalized.contains("]")
            || normalized.contains("count.index")
            || normalized.contains("each.")
            || normalized.contains(" for ")
            || normalized.contains("lookup(")
            || normalized.contains("format(")
            || normalized.contains("length(")
            || normalized.contains("contains(")
            || normalized.contains("keys(")
            || normalized.contains("values(");
    }

    private static Integer parseLiteralIntegerHelper(String expression) {
        if (!expression.chars().allMatch(character -> Character.isDigit(character) || character == '-')) {
            return null;
        }
        try {
            return Integer.parseInt(expression);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String normalizeExpression(String expression) {
        if (expression == null) {
            return null;
        }
        String normalized = expression.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.startsWith("${") && normalized.endsWith("}")) {
            normalized = normalized.substring(2, normalized.length() - 1).trim();
        }
        return normalized.isEmpty() ? null : normalized;
    }
}
