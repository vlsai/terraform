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
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.stream.Collectors;

/**
 * 模板领域对象。
 *
 * <p>当前实现不再把 zip 内所有目录都直接当成已部署资源，而是从 root module 出发，
 * 根据 `module` 调用关系实例化子模块，并在实例级别解析 resource/data。
 */
@Getter
@Log4j2
public class TerraformTemplate {

    private static final String ECS_RESOURCE_TYPE = "ECS";
    private static final String EVS_RESOURCE_TYPE = "EVS";
    private static final String ECS_RESOURCE_TYPE_KEY = QuotaCheckRule.normalizeResourceType(ECS_RESOURCE_TYPE);
    private static final String EVS_RESOURCE_TYPE_KEY = QuotaCheckRule.normalizeResourceType(EVS_RESOURCE_TYPE);
    private static final int DEFAULT_COUNT = 1;
    private static final int DEFAULT_SYSTEM_DISK_GIB = 40;
    private static final int DEFAULT_DATA_DISK_GIB = 40;
    private static final int DEFAULT_EVS_VOLUME_GIB = 40;
    private static final java.util.regex.Pattern VARIABLE_REFERENCE_PATTERN =
        java.util.regex.Pattern.compile("var\\.([A-Za-z0-9_]+)");
    private static final java.util.regex.Pattern LOCAL_REFERENCE_PATTERN =
        java.util.regex.Pattern.compile("local\\.([A-Za-z0-9_]+)");

    private final String fileId;

    /**
     * Key 格式为 `moduleInstancePath:sourceName:providerType:providerName:blockName`，
     * 用于区分同一子模块被多次实例化时的资源实例。
     */
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
                TerraformFileParser.ParseResult parseResult;
                try {
                    TerraformFileParser parser = parserFor(entryName, parsers);
                    parseResult = parser.parse(new ByteArrayInputStream(content), entryName);
                } catch (IOException | RuntimeException exception) {
                    log.error("Skip zip entry because parser could not parse it. file={}, reason={}",
                        entryName, exception.getMessage(), exception);
                    continue;
                }

                moduleAggregations.computeIfAbsent(parentDirectory(entryName), ignored -> new ModuleAggregation())
                    .merge(parseResult, entryName);
            }
        }

        String rootModuleDirectory = resolveRootModuleDirectory(moduleAggregations);
        ModuleAggregation rootAggregation = moduleAggregations.get(rootModuleDirectory);
        instantiateModule(
            rootModuleDirectory,
            moduleAggregations,
            Map.of(),
            "root",
            new LinkedHashSet<>(),
            rootAggregation == null ? Set.of() : rootAggregation.runtimeVariableNames()
        );
    }

    private void parseSingleFile(InputStream inputStream, String fileName, List<TerraformFileParser> parsers) throws IOException {
        TerraformFileParser parser = parserFor(fileName, parsers);
        TerraformFileParser.ParseResult parseResult = parser.parse(inputStream, fileName);
        ModuleAggregation moduleAggregation = new ModuleAggregation();
        moduleAggregation.merge(parseResult, fileName);

        ResolvedModule resolvedModule = moduleAggregation.resolve(Map.of(), "root", moduleAggregation.runtimeVariableNames());
        resolvedModule.actions().forEach(actionWithSource ->
            addAction(actionWithSource.action, "root:" + actionWithSource.sourceName));
    }

    private void instantiateModule(
        String moduleDirectory,
        Map<String, ModuleAggregation> moduleAggregations,
        Map<String, Object> inputValues,
        String moduleInstancePath,
        LinkedHashSet<String> activeModuleStack,
        Set<String> runtimeVariableNames
    ) {
        String normalizedDirectory = moduleDirectory == null ? "" : moduleDirectory;
        ModuleAggregation aggregation = moduleAggregations.get(normalizedDirectory);
        if (aggregation == null) {
            log.info("Skip module instantiation because module directory was not found. moduleDirectory={}, instance={}",
                normalizedDirectory, moduleInstancePath);
            return;
        }

        if (!activeModuleStack.add(normalizedDirectory + "@" + moduleInstancePath)) {
            log.info("Detected module recursion. moduleDirectory={}, instance={}", normalizedDirectory, moduleInstancePath);
            return;
        }

        try {
            ResolvedModule resolvedModule = aggregation.resolve(inputValues, moduleInstancePath, runtimeVariableNames);
            resolvedModule.actions().forEach(actionWithSource ->
                addAction(actionWithSource.action, moduleInstancePath + ":" + actionWithSource.sourceName));

            for (ResolvedModuleCall moduleCall : resolvedModule.moduleCalls()) {
                if (moduleCall.source() == null || moduleCall.source().isBlank()) {
                    continue;
                }
                if (!isLocalModuleSource(moduleCall.source())) {
                    continue;
                }
                String childModuleDirectory = resolveModuleSource(normalizedDirectory, moduleCall.source());
                instantiateModule(
                    childModuleDirectory,
                    moduleAggregations,
                    moduleCall.inputValues(),
                    moduleInstancePath + ".module." + moduleCall.moduleName(),
                    activeModuleStack,
                    moduleCall.runtimeVariableInputs()
                );
            }
        } finally {
            activeModuleStack.remove(normalizedDirectory + "@" + moduleInstancePath);
        }
    }

    private String resolveRootModuleDirectory(Map<String, ModuleAggregation> moduleAggregations) {
        if (moduleAggregations.containsKey("")) {
            return "";
        }
        return moduleAggregations.keySet().stream()
            .sorted((left, right) -> Integer.compare(left.length(), right.length()))
            .findFirst()
            .orElse("");
    }

    private boolean isLocalModuleSource(String source) {
        String normalized = normalizeExpression(source);
        return normalized != null && (normalized.startsWith("./") || normalized.startsWith("../"));
    }

    private String resolveModuleSource(String currentModuleDirectory, String source) {
        String normalizedCurrentDirectory = currentModuleDirectory == null ? "" : currentModuleDirectory;
        String normalizedSource = normalizeExpression(source);
        if (normalizedSource == null) {
            return normalizedCurrentDirectory;
        }
        if (normalizedCurrentDirectory.isBlank()) {
            return normalizeArchivePath(normalizedSource);
        }
        return normalizeArchivePath(normalizedCurrentDirectory + "/" + normalizedSource);
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

    public TemplateAnalysisResult extractQuotas(
        QuotaCheckRule quotaRules,
        Map<ProviderUsageKey, List<ProviderConfig>> providerConfigsByProviderUsage
    ) {
        Map<ProviderUsageKey, List<ProviderConfig>> providerConfigIndex = normalizeProviderConfigIndex(providerConfigsByProviderUsage);
        List<TemplateProvider> templateProvidersList = buildTemplateProviders(providerConfigIndex);
        Map<QuotaResourceKey, Integer> quotaRequirements = calculateQuotaRequirements(quotaRules, providerConfigIndex);
        List<TemplateQuotaResource> quotaResourcesList = toTemplateQuotaResources(quotaRequirements);
        return new TemplateAnalysisResult()
            .setTemplateId(fileId)
            .setProviders(templateProvidersList)
            .setQuotaResources(new ArrayList<>(quotaResourcesList));
    }

    private Map<ProviderUsageKey, List<ProviderConfig>> normalizeProviderConfigIndex(
        Map<ProviderUsageKey, List<ProviderConfig>> providerConfigsByProviderUsage
    ) {
        return providerConfigsByProviderUsage == null ? Map.of() : providerConfigsByProviderUsage;
    }

    private List<TemplateProvider> buildTemplateProviders(Map<ProviderUsageKey, List<ProviderConfig>> providerConfigIndex) {
        Set<ProviderUsageKey> usedProviderUsages = collectUsedProviderUsages();
        List<TemplateProvider> templateProvidersList = new ArrayList<>();
        for (ProviderUsageKey usedProviderUsage : usedProviderUsages) {
            addTemplateProviderIfPresetExists(usedProviderUsage, providerConfigIndex, templateProvidersList);
        }
        return templateProvidersList;
    }

    private void addTemplateProviderIfPresetExists(
        ProviderUsageKey usedProviderUsage,
        Map<ProviderUsageKey, List<ProviderConfig>> providerConfigIndex,
        List<TemplateProvider> templateProvidersList
    ) {
        List<ProviderConfig> providerConfigs = providerConfigIndex.get(usedProviderUsage);
        if (providerConfigs == null || providerConfigs.isEmpty()) {
            log.info("Provider usage was used in the template but was not found in the preset table. providerName={}, providerType={}",
                usedProviderUsage.getProviderName(),
                usedProviderUsage.getProviderType() == null ? null : usedProviderUsage.getProviderType().getDbValue());
            return;
        }
        templateProvidersList.add(new TemplateProvider()
            .setTemplateId(fileId)
            .setProviderName(usedProviderUsage.getProviderName())
            .setProviderType(usedProviderUsage.getProviderType().getDbValue()));
    }

    private Map<QuotaResourceKey, Integer> calculateQuotaRequirements(
        QuotaCheckRule quotaRules,
        Map<ProviderUsageKey, List<ProviderConfig>> providerConfigIndex
    ) {
        Map<QuotaResourceKey, Integer> quotaRequirements = new LinkedHashMap<>();
        if (quotaRules == null || quotaRules.getCloudService() == null) {
            return quotaRequirements;
        }
        Map<String, List<TerraformAction>> resourceActionsByResourceType = indexResourceActionsByResourceType(providerConfigIndex);
        for (QuotaCheckRule.CloudServiceRule rule : quotaRules.getCloudService()) {
            if (rule == null) {
                continue;
            }
            applyQuotaRule(rule, resourceActionsByResourceType, providerConfigIndex, quotaRequirements);
        }
        return quotaRequirements;
    }

    private List<TemplateQuotaResource> toTemplateQuotaResources(Map<QuotaResourceKey, Integer> quotaRequirements) {
        return quotaRequirements.entrySet().stream()
            .map(entry -> new TemplateQuotaResource()
                .setTemplateId(fileId)
                .setResourceType(entry.getKey().resourceType)
                .setQuotaType(entry.getKey().quotaType)
                .setQuotaRequirement(entry.getValue()))
            .toList();
    }

    private Set<ProviderUsageKey> collectUsedProviderUsages() {
        Set<ProviderUsageKey> usedProviderUsages = new LinkedHashSet<>();
        for (TerraformAction action : actions.values()) {
            if (action == null
                || action.getProviderName() == null
                || action.getProviderName().isBlank()
                || action.getProviderType() == null) {
                continue;
            }
            usedProviderUsages.add(new ProviderUsageKey(action.getProviderName(), action.getProviderType()));
        }
        return usedProviderUsages;
    }

    private Map<String, List<TerraformAction>> indexResourceActionsByResourceType(
        Map<ProviderUsageKey, List<ProviderConfig>> providerConfigIndex
    ) {
        Map<String, List<TerraformAction>> indexed = new LinkedHashMap<>();
        for (TerraformAction action : actions.values()) {
            if (action == null || action.getProviderType() != ProviderType.RESOURCE) {
                continue;
            }
            if (action.getProviderName() == null || action.getProviderName().isBlank()) {
                continue;
            }

            ProviderUsageKey usageKey = new ProviderUsageKey(action.getProviderName(), action.getProviderType());
            List<ProviderConfig> providerConfigs = providerConfigIndex.get(usageKey);
            if (providerConfigs == null || providerConfigs.isEmpty()) {
                continue;
            }

            Set<String> mappedResourceTypes = providerConfigs.stream()
                .filter(providerConfig -> providerConfig.getProviderType() == ProviderType.RESOURCE)
                .filter(ProviderConfig::isPrimaryQuotaSubject)
                .map(ProviderConfig::getResourceType)
                .map(QuotaCheckRule::normalizeResourceType)
                .filter(resourceType -> resourceType != null && !resourceType.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));

            for (String mappedResourceType : mappedResourceTypes) {
                indexed.computeIfAbsent(mappedResourceType, ignored -> new ArrayList<>()).add(action);
            }
        }
        return indexed;
    }

    private void applyQuotaRule(
        QuotaCheckRule.CloudServiceRule rule,
        Map<String, List<TerraformAction>> resourceActionsByResourceType,
        Map<ProviderUsageKey, List<ProviderConfig>> providerConfigIndex,
        Map<QuotaResourceKey, Integer> quotaRequirements
    ) {
        String normalizedResourceType = QuotaCheckRule.normalizeResourceType(rule.getResourceType());
        if (normalizedResourceType == null || normalizedResourceType.isBlank()) {
            return;
        }

        if (ECS_RESOURCE_TYPE_KEY.equals(normalizedResourceType)) {
            for (TerraformAction action : resourceActionsByResourceType.getOrDefault(ECS_RESOURCE_TYPE_KEY, List.of())) {
                addEcsQuotas(action, rule, quotaRequirements);
            }
            return;
        }

        if (EVS_RESOURCE_TYPE_KEY.equals(normalizedResourceType)) {
            for (TerraformAction action : resourceActionsByResourceType.getOrDefault(EVS_RESOURCE_TYPE_KEY, List.of())) {
                addEvsVolumeQuotas(action, rule, quotaRequirements);
            }
            for (TerraformAction action : resourceActionsByResourceType.getOrDefault(ECS_RESOURCE_TYPE_KEY, List.of())) {
                addEvsQuotasFromEcs(action, rule, quotaRequirements);
            }
            return;
        }

        for (TerraformAction action : resourceActionsByResourceType.getOrDefault(normalizedResourceType, List.of())) {
            addGenericQuotas(action, rule, providerConfigIndex, quotaRequirements);
        }
    }

    private void addGenericQuotas(
        TerraformAction action,
        QuotaCheckRule.CloudServiceRule rule,
        Map<ProviderUsageKey, List<ProviderConfig>> providerConfigIndex,
        Map<QuotaResourceKey, Integer> quotaRequirements
    ) {
        List<String> matchedQuotaTypes = selectGenericQuotaTypes(action, rule, providerConfigIndex);
        for (String quotaType : matchedQuotaTypes) {
            mergeQuotaRequirement(quotaRequirements, rule.getResourceType(), quotaType, action.getRequestedAmount());
        }
    }

    private List<String> selectGenericQuotaTypes(
        TerraformAction action,
        QuotaCheckRule.CloudServiceRule rule,
        Map<ProviderUsageKey, List<ProviderConfig>> providerConfigIndex
    ) {
        if (rule.getQuotaType() == null || rule.getQuotaType().isEmpty()) {
            return List.of();
        }
        if (rule.getQuotaType().size() == 1) {
            return rule.getQuotaType();
        }

        List<String> hintedQuotaTypes = selectHintedQuotaTypes(action, rule, providerConfigIndex);
        if (!hintedQuotaTypes.isEmpty()) {
            return hintedQuotaTypes;
        }

        String normalizedProviderName = normalizeKey(action.getProviderName());
        List<String> matched = rule.getQuotaType().stream()
            .filter(quotaType -> quotaTypeMatchesProvider(normalizedProviderName, quotaType))
            .distinct()
            .toList();
        if (!matched.isEmpty()) {
            return matched;
        }

        return List.of();
    }

    private List<String> selectHintedQuotaTypes(
        TerraformAction action,
        QuotaCheckRule.CloudServiceRule rule,
        Map<ProviderUsageKey, List<ProviderConfig>> providerConfigIndex
    ) {
        if (action == null
            || action.getProviderName() == null
            || action.getProviderName().isBlank()
            || action.getProviderType() == null
            || providerConfigIndex == null
            || providerConfigIndex.isEmpty()) {
            return List.of();
        }

        List<ProviderConfig> providerConfigs = providerConfigIndex.get(new ProviderUsageKey(action.getProviderName(), action.getProviderType()));
        if (providerConfigs == null || providerConfigs.isEmpty()) {
            return List.of();
        }

        Map<String, String> normalizedRuleQuotaTypes = rule.getQuotaType().stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .collect(Collectors.toMap(
                this::normalizeKey,
                quotaType -> quotaType,
                (left, right) -> left,
                LinkedHashMap::new
            ));
        if (normalizedRuleQuotaTypes.isEmpty()) {
            return List.of();
        }

        List<String> hinted = providerConfigs.stream()
            .map(ProviderConfig::getQuotaTypeHint)
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .map(hint -> normalizedRuleQuotaTypes.get(normalizeKey(hint)))
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (hinted.isEmpty()) {
            return List.of();
        }
        return hinted;
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
        FlavorSpec flavorSpec = resolveEcsFlavorSpec(action);

        for (String quotaType : rule.getQuotaType()) {
            String normalizedQuotaType = normalizeKey(quotaType);
            if (normalizedQuotaType.contains("cpu")) {
                mergeQuotaRequirement(
                    quotaRequirements,
                    rule.getResourceType(),
                    quotaType,
                    action.getRequestedAmount() * flavorSpec.cpuCount
                );
                continue;
            }
            if (normalizedQuotaType.contains("ram") || normalizedQuotaType.contains("memory")) {
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

    private FlavorSpec resolveEcsFlavorSpec(TerraformAction action) {
        if (action == null) {
            return new FlavorSpec(1, 1);
        }
        if (action.getExplicitCpuCount() != null
            && action.getExplicitCpuCount() > 0
            && action.getExplicitMemoryGiB() != null
            && action.getExplicitMemoryGiB() > 0) {
            return new FlavorSpec(action.getExplicitCpuCount(), action.getExplicitMemoryGiB());
        }

        FlavorSpec parsedFlavorSpec = parseFlavorSpec(action.getFlavorId());
        if (parsedFlavorSpec != null) {
            return parsedFlavorSpec;
        }
        return new FlavorSpec(1, 1);
    }

    private void addEvsQuotasFromEcs(
        TerraformAction action,
        QuotaCheckRule.CloudServiceRule evsRule,
        Map<QuotaResourceKey, Integer> quotaRequirements
    ) {
        if (evsRule == null || action == null) {
            return;
        }

        int systemDiskGiB = action.getSystemDiskSize() == null || action.getSystemDiskSize() <= 0
            ? DEFAULT_SYSTEM_DISK_GIB
            : action.getSystemDiskSize();
        List<Integer> dataDiskSizes = action.getDataDiskSizes() == null ? List.of() : action.getDataDiskSizes();
        int dataDiskCount = dataDiskSizes.size();
        int dataDiskTotalGiB = dataDiskSizes.stream()
            .filter(Objects::nonNull)
            .mapToInt(size -> size > 0 ? size : DEFAULT_DATA_DISK_GIB)
            .sum();

        int volumeCountPerInstance = 1 + dataDiskCount;
        int totalDiskGiBPerInstance = systemDiskGiB + dataDiskTotalGiB;

        for (String quotaType : evsRule.getQuotaType()) {
            String normalizedQuotaType = normalizeKey(quotaType);
            if (normalizedQuotaType.contains("gigabyte") || normalizedQuotaType.equals("gb")) {
                mergeQuotaRequirement(
                    quotaRequirements,
                    evsRule.getResourceType(),
                    quotaType,
                    action.getRequestedAmount() * totalDiskGiBPerInstance
                );
                continue;
            }
            mergeQuotaRequirement(
                quotaRequirements,
                evsRule.getResourceType(),
                quotaType,
                action.getRequestedAmount() * volumeCountPerInstance
            );
        }
    }

    private void addEvsVolumeQuotas(
        TerraformAction action,
        QuotaCheckRule.CloudServiceRule rule,
        Map<QuotaResourceKey, Integer> quotaRequirements
    ) {
        int volumeSize = action.getVolumeSize() == null || action.getVolumeSize() <= 0
            ? DEFAULT_EVS_VOLUME_GIB
            : action.getVolumeSize();
        for (String quotaType : rule.getQuotaType()) {
            String normalizedQuotaType = normalizeKey(quotaType);
            if (normalizedQuotaType.contains("gigabyte") || normalizedQuotaType.equals("gb")) {
                mergeQuotaRequirement(
                    quotaRequirements,
                    rule.getResourceType(),
                    quotaType,
                    action.getRequestedAmount() * volumeSize
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

        String normalizedFlavorId = flavorId.trim().toLowerCase(Locale.ROOT);
        java.util.regex.Matcher explicitCpuMemory = java.util.regex.Pattern
            .compile(".*\\.(\\d+)u\\.(\\d+)g$")
            .matcher(normalizedFlavorId);
        if (explicitCpuMemory.matches()) {
            return new FlavorSpec(
                Integer.parseInt(explicitCpuMemory.group(1)),
                Integer.parseInt(explicitCpuMemory.group(2))
            );
        }

        String[] segments = normalizedFlavorId.split("\\.");
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

    private static final class ModuleAggregation {
        private final Map<String, Object> localValues = new LinkedHashMap<>();
        private final Map<String, Object> variableDefaults = new LinkedHashMap<>();
        private final List<ActionWithSource> gatheredActions = new ArrayList<>();
        private final List<ModuleCallWithSource> gatheredModuleCalls = new ArrayList<>();

        private void merge(TerraformFileParser.ParseResult parseResult, String sourceName) {
            if (parseResult == null) {
                return;
            }
            if (parseResult.getLocalValues() != null) {
                parseResult.getLocalValues().forEach(localValues::put);
            }
            if (parseResult.getVariableDefaults() != null) {
                parseResult.getVariableDefaults().forEach(variableDefaults::put);
            }
            if (parseResult.getActions() != null) {
                parseResult.getActions().forEach(action -> gatheredActions.add(new ActionWithSource(action, sourceName)));
            }
            if (parseResult.getModuleCalls() != null) {
                parseResult.getModuleCalls().forEach(moduleCall -> gatheredModuleCalls.add(new ModuleCallWithSource(moduleCall, sourceName)));
            }
        }

        private Set<String> runtimeVariableNames() {
            return new LinkedHashSet<>(variableDefaults.keySet());
        }

        private ResolvedModule resolve(
            Map<String, Object> inputValues,
            String moduleInstancePath,
            Set<String> runtimeVariableNames
        ) {
            Map<String, Object> effectiveVariables = buildEffectiveVariables(inputValues);
            Set<String> effectiveRuntimeVariables = buildEffectiveRuntimeVariables(runtimeVariableNames);
            List<ActionWithSource> resolvedActions = resolveActions(effectiveVariables, effectiveRuntimeVariables);
            List<ResolvedModuleCall> resolvedModuleCalls = resolveModuleCalls(
                moduleInstancePath,
                effectiveVariables,
                effectiveRuntimeVariables
            );
            return new ResolvedModule(resolvedActions, resolvedModuleCalls);
        }

        private Map<String, Object> buildEffectiveVariables(Map<String, Object> inputValues) {
            Map<String, Object> effectiveVariables = new LinkedHashMap<>(variableDefaults);
            if (inputValues != null) {
                inputValues.forEach(effectiveVariables::put);
            }
            return effectiveVariables;
        }

        private Set<String> buildEffectiveRuntimeVariables(Set<String> runtimeVariableNames) {
            if (runtimeVariableNames == null) {
                return Set.of();
            }
            return new LinkedHashSet<>(runtimeVariableNames);
        }

        private List<ActionWithSource> resolveActions(
            Map<String, Object> effectiveVariables,
            Set<String> effectiveRuntimeVariables
        ) {
            List<ActionWithSource> resolvedActions = new ArrayList<>();
            for (ActionWithSource actionWithSource : gatheredActions) {
                ActionWithSource resolvedAction = resolveAction(actionWithSource, effectiveVariables, effectiveRuntimeVariables);
                if (resolvedAction != null) {
                    resolvedActions.add(resolvedAction);
                }
            }
            return resolvedActions;
        }

        private ActionWithSource resolveAction(
            ActionWithSource actionWithSource,
            Map<String, Object> effectiveVariables,
            Set<String> effectiveRuntimeVariables
        ) {
            TerraformAction originalAction = actionWithSource == null ? null : actionWithSource.action;
            if (originalAction == null) {
                return null;
            }

            TerraformAction action = copyAction(originalAction);
            if (isDataAction(action)) {
                return resolveDataAction(action, actionWithSource.sourceName);
            }

            int requestedAmount = resolveRequestedAmount(
                action.getRequestedAmountExpression(),
                localValues,
                effectiveVariables,
                actionWithSource.sourceName,
                effectiveRuntimeVariables
            );
            if (requestedAmount <= 0) {
                return null;
            }
            action.setRequestedAmount(requestedAmount);
            populateResolvedActionFields(action, actionWithSource.sourceName, effectiveVariables);
            return new ActionWithSource(action, actionWithSource.sourceName);
        }

        private boolean isDataAction(TerraformAction action) {
            return action.getProviderType() == ProviderType.DATA;
        }

        private ActionWithSource resolveDataAction(TerraformAction action, String sourceName) {
            action.setRequestedAmount(1);
            action.setRequestedAmountExpression(null);
            return new ActionWithSource(action, sourceName);
        }

        private void populateResolvedActionFields(
            TerraformAction action,
            String sourceName,
            Map<String, Object> effectiveVariables
        ) {
            action.setExplicitCpuCount(resolveIntegerExpression(
                effectiveVariables.get("instance_flavor_cpu"),
                localValues,
                effectiveVariables,
                sourceName,
                "instance_flavor_cpu"
            ));
            action.setExplicitMemoryGiB(resolveIntegerExpression(
                effectiveVariables.get("instance_flavor_memory"),
                localValues,
                effectiveVariables,
                sourceName,
                "instance_flavor_memory"
            ));
            action.setFlavorId(resolveStringExpression(
                action.getFlavorIdExpression(),
                localValues,
                effectiveVariables,
                sourceName,
                "flavor_id"
            ));
            action.setSystemDiskSize(resolveIntegerExpression(
                action.getSystemDiskSizeExpression(),
                localValues,
                effectiveVariables,
                sourceName,
                "system_disk_size"
            ));
            action.setVolumeSize(resolveIntegerExpression(
                action.getVolumeSizeExpression(),
                localValues,
                effectiveVariables,
                sourceName,
                "size"
            ));
            action.setDataDiskSizes(resolveDiskSizes(
                effectiveVariables.get("data_disks"),
                localValues,
                effectiveVariables,
                sourceName
            ));
        }

        private List<ResolvedModuleCall> resolveModuleCalls(
            String moduleInstancePath,
            Map<String, Object> effectiveVariables,
            Set<String> effectiveRuntimeVariables
        ) {
            List<ResolvedModuleCall> resolvedModuleCalls = new ArrayList<>();
            for (ModuleCallWithSource moduleCallWithSource : gatheredModuleCalls) {
                ResolvedModuleCall resolvedModuleCall = resolveModuleCall(
                    moduleCallWithSource,
                    moduleInstancePath,
                    effectiveVariables,
                    effectiveRuntimeVariables
                );
                if (resolvedModuleCall != null) {
                    resolvedModuleCalls.add(resolvedModuleCall);
                }
            }
            return resolvedModuleCalls;
        }

        private ResolvedModuleCall resolveModuleCall(
            ModuleCallWithSource moduleCallWithSource,
            String moduleInstancePath,
            Map<String, Object> effectiveVariables,
            Set<String> effectiveRuntimeVariables
        ) {
            TerraformModuleCall moduleCall = moduleCallWithSource == null ? null : moduleCallWithSource.moduleCall;
            if (moduleCall == null || moduleCall.getModuleName() == null) {
                return null;
            }

            Map<String, Object> resolvedInputs = new LinkedHashMap<>();
            Set<String> runtimeVariableInputs = new LinkedHashSet<>();
            resolveModuleInputs(moduleCall, moduleInstancePath, effectiveVariables, effectiveRuntimeVariables, resolvedInputs, runtimeVariableInputs);
            return new ResolvedModuleCall(
                moduleCall.getModuleName(),
                normalizeExpression(moduleCall.getSource()),
                resolvedInputs,
                runtimeVariableInputs
            );
        }

        private void resolveModuleInputs(
            TerraformModuleCall moduleCall,
            String moduleInstancePath,
            Map<String, Object> effectiveVariables,
            Set<String> effectiveRuntimeVariables,
            Map<String, Object> resolvedInputs,
            Set<String> runtimeVariableInputs
        ) {
            if (moduleCall.getInputValues() == null) {
                return;
            }
            moduleCall.getInputValues().forEach((inputName, rawValue) -> {
                resolvedInputs.put(
                    inputName,
                    resolveObjectValue(rawValue, localValues, effectiveVariables, moduleInstancePath, inputName)
                );
                if (dependsOnRuntimeVariable(rawValue, localValues, effectiveRuntimeVariables)) {
                    runtimeVariableInputs.add(inputName);
                }
            });
        }

        private TerraformAction copyAction(TerraformAction action) {
            return new TerraformAction()
                .setProviderName(action.getProviderName())
                .setBlockName(action.getBlockName())
                .setProviderType(action.getProviderType())
                .setRequestedAmount(action.getRequestedAmount())
                .setRequestedAmountExpression(action.getRequestedAmountExpression())
                .setExplicitCpuCount(action.getExplicitCpuCount())
                .setExplicitMemoryGiB(action.getExplicitMemoryGiB())
                .setFlavorId(action.getFlavorId())
                .setFlavorIdExpression(action.getFlavorIdExpression())
                .setSystemDiskSize(action.getSystemDiskSize())
                .setSystemDiskSizeExpression(action.getSystemDiskSizeExpression())
                .setDataDiskSizes(action.getDataDiskSizes() == null ? new ArrayList<>() : new ArrayList<>(action.getDataDiskSizes()))
                .setVolumeSize(action.getVolumeSize())
                .setVolumeSizeExpression(action.getVolumeSizeExpression());
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

    private static final class ModuleCallWithSource {
        private final TerraformModuleCall moduleCall;
        private final String sourceName;

        private ModuleCallWithSource(TerraformModuleCall moduleCall, String sourceName) {
            this.moduleCall = moduleCall;
            this.sourceName = sourceName;
        }
    }

    private record ResolvedModule(List<ActionWithSource> actions, List<ResolvedModuleCall> moduleCalls) {
    }

    private record ResolvedModuleCall(
        String moduleName,
        String source,
        Map<String, Object> inputValues,
        Set<String> runtimeVariableInputs
    ) {
    }

    private static int resolveRequestedAmount(
        String expression,
        Map<String, Object> localValues,
        Map<String, Object> variableValues,
        String sourceName,
        Set<String> runtimeVariableNames
    ) {
        if (dependsOnRuntimeVariable(expression, localValues, runtimeVariableNames)) {
            return DEFAULT_COUNT;
        }
        Integer resolved = resolveIntegerExpression(expression, localValues, variableValues, sourceName, "count");
        return resolved == null ? DEFAULT_COUNT : Math.max(resolved, 0);
    }

    private static Integer resolveIntegerExpression(
        Object expression,
        Map<String, Object> localValues,
        Map<String, Object> variableValues,
        String sourceName,
        String fieldName
    ) {
        Object resolvedValue = resolveObjectValue(expression, localValues, variableValues, sourceName, fieldName);
        return coerceInteger(resolvedValue);
    }

    private static String resolveStringExpression(
        Object expression,
        Map<String, Object> localValues,
        Map<String, Object> variableValues,
        String sourceName,
        String fieldName
    ) {
        Object resolvedValue = resolveObjectValue(expression, localValues, variableValues, sourceName, fieldName);
        if (resolvedValue == null) {
            return null;
        }
        return resolvedValue instanceof String string ? normalizeExpression(string) : String.valueOf(resolvedValue);
    }

    private static List<Integer> resolveDiskSizes(
        Object expression,
        Map<String, Object> localValues,
        Map<String, Object> variableValues,
        String sourceName
    ) {
        Object resolvedValue = resolveObjectValue(expression, localValues, variableValues, sourceName, "data_disks");
        if (!(resolvedValue instanceof List<?> diskDefinitions)) {
            return List.of();
        }

        List<Integer> diskSizes = new ArrayList<>();
        for (Object diskDefinition : diskDefinitions) {
            if (!(diskDefinition instanceof Map<?, ?> diskAttributes)) {
                continue;
            }
            Integer diskSize = coerceInteger(((Map<?, ?>) diskAttributes).get("data_disk_size"));
            diskSizes.add(diskSize == null || diskSize <= 0 ? DEFAULT_DATA_DISK_GIB : diskSize);
        }
        return diskSizes;
    }

    private static Object resolveObjectValue(
        Object expression,
        Map<String, Object> localValues,
        Map<String, Object> variableValues,
        String sourceName,
        String fieldName
    ) {
        return resolveObjectValue(expression, localValues, variableValues, sourceName, fieldName, new LinkedHashSet<>(), new LinkedHashSet<>());
    }

    private static Object resolveObjectValue(
        Object expression,
        Map<String, Object> localValues,
        Map<String, Object> variableValues,
        String sourceName,
        String fieldName,
        LinkedHashSet<String> visitedLocals,
        LinkedHashSet<String> visitedVariables
    ) {
        if (expression == null) {
            return null;
        }
        if (expression instanceof Number || expression instanceof Boolean) {
            return expression;
        }
        if (expression instanceof List<?> list) {
            List<Object> resolved = new ArrayList<>();
            list.forEach(item -> resolved.add(resolveObjectValue(item, localValues, variableValues, sourceName, fieldName, visitedLocals, visitedVariables)));
            return resolved;
        }
        if (expression instanceof Map<?, ?> map) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                if (key != null) {
                    resolved.put(
                        String.valueOf(key),
                        resolveObjectValue(value, localValues, variableValues, sourceName, fieldName, visitedLocals, visitedVariables)
                    );
                }
            });
            return resolved;
        }
        if (!(expression instanceof String rawExpression)) {
            return expression;
        }
        return evaluateExpression(rawExpression, localValues, variableValues, sourceName, fieldName, visitedLocals, visitedVariables);
    }

    private static boolean dependsOnRuntimeVariable(
        Object expression,
        Map<String, Object> localValues,
        Set<String> runtimeVariableNames
    ) {
        return dependsOnRuntimeVariable(expression, localValues, runtimeVariableNames, new LinkedHashSet<>());
    }

    private static boolean dependsOnRuntimeVariable(
        Object expression,
        Map<String, Object> localValues,
        Set<String> runtimeVariableNames,
        LinkedHashSet<String> visitedLocals
    ) {
        if (!canCheckRuntimeDependency(expression, runtimeVariableNames)) {
            return false;
        }
        if (expression instanceof Number || expression instanceof Boolean) {
            return false;
        }
        if (expression instanceof List<?> list) {
            return dependsOnRuntimeVariableInList(list, localValues, runtimeVariableNames, visitedLocals);
        }
        if (expression instanceof Map<?, ?> map) {
            return dependsOnRuntimeVariableInMap(map, localValues, runtimeVariableNames, visitedLocals);
        }
        if (!(expression instanceof String rawExpression)) {
            return false;
        }
        return dependsOnRuntimeVariableInString(rawExpression, localValues, runtimeVariableNames, visitedLocals);
    }

    private static boolean canCheckRuntimeDependency(Object expression, Set<String> runtimeVariableNames) {
        return expression != null && runtimeVariableNames != null && !runtimeVariableNames.isEmpty();
    }

    private static boolean dependsOnRuntimeVariableInList(
        List<?> expressions,
        Map<String, Object> localValues,
        Set<String> runtimeVariableNames,
        LinkedHashSet<String> visitedLocals
    ) {
        return expressions.stream()
            .anyMatch(item -> dependsOnRuntimeVariable(item, localValues, runtimeVariableNames, visitedLocals));
    }

    private static boolean dependsOnRuntimeVariableInMap(
        Map<?, ?> expressionMap,
        Map<String, Object> localValues,
        Set<String> runtimeVariableNames,
        LinkedHashSet<String> visitedLocals
    ) {
        return expressionMap.values().stream()
            .anyMatch(value -> dependsOnRuntimeVariable(value, localValues, runtimeVariableNames, visitedLocals));
    }

    private static boolean dependsOnRuntimeVariableInString(
        String rawExpression,
        Map<String, Object> localValues,
        Set<String> runtimeVariableNames,
        LinkedHashSet<String> visitedLocals
    ) {
        String normalizedExpression = normalizeExpression(rawExpression);
        if (normalizedExpression == null) {
            return false;
        }
        if (isSimpleReference(normalizedExpression, "var.")) {
            return runtimeVariableNames.contains(normalizedExpression.substring("var.".length()));
        }
        if (isSimpleReference(normalizedExpression, "local.")) {
            return dependsOnRuntimeVariableByLocalReference(
                normalizedExpression.substring("local.".length()),
                localValues,
                runtimeVariableNames,
                visitedLocals
            );
        }
        if (containsRuntimeVariableReference(normalizedExpression, runtimeVariableNames)) {
            return true;
        }
        return dependsOnReferencedLocals(normalizedExpression, localValues, runtimeVariableNames, visitedLocals);
    }

    private static boolean dependsOnRuntimeVariableByLocalReference(
        String localName,
        Map<String, Object> localValues,
        Set<String> runtimeVariableNames,
        LinkedHashSet<String> visitedLocals
    ) {
        if (!visitedLocals.add(localName)) {
            return false;
        }
        return dependsOnRuntimeVariable(
            localValues == null ? null : localValues.get(localName),
            localValues,
            runtimeVariableNames,
            visitedLocals
        );
    }

    private static boolean containsRuntimeVariableReference(String normalizedExpression, Set<String> runtimeVariableNames) {
        java.util.regex.Matcher variableMatcher = VARIABLE_REFERENCE_PATTERN.matcher(normalizedExpression);
        while (variableMatcher.find()) {
            if (runtimeVariableNames.contains(variableMatcher.group(1))) {
                return true;
            }
        }
        return false;
    }

    private static boolean dependsOnReferencedLocals(
        String normalizedExpression,
        Map<String, Object> localValues,
        Set<String> runtimeVariableNames,
        LinkedHashSet<String> visitedLocals
    ) {
        java.util.regex.Matcher localMatcher = LOCAL_REFERENCE_PATTERN.matcher(normalizedExpression);
        while (localMatcher.find()) {
            String localName = localMatcher.group(1);
            if (!visitedLocals.add(localName)) {
                continue;
            }
            if (dependsOnRuntimeVariable(
                localValues == null ? null : localValues.get(localName),
                localValues,
                runtimeVariableNames,
                visitedLocals
            )) {
                return true;
            }
        }
        return false;
    }

    private static Object evaluateExpression(
        String expression,
        Map<String, Object> localValues,
        Map<String, Object> variableValues,
        String sourceName,
        String fieldName,
        LinkedHashSet<String> visitedLocals,
        LinkedHashSet<String> visitedVariables
    ) {
        String normalizedExpression = normalizeExpression(expression);
        if (normalizedExpression == null) {
            return null;
        }

        String strippedParentheses = stripOuterParentheses(normalizedExpression);
        if (!Objects.equals(strippedParentheses, normalizedExpression)) {
            return evaluateExpression(strippedParentheses, localValues, variableValues, sourceName, fieldName, visitedLocals, visitedVariables);
        }

        Object directReferenceValue = evaluateDirectReference(
            normalizedExpression,
            localValues,
            variableValues,
            sourceName,
            fieldName,
            visitedLocals,
            visitedVariables
        );
        if (directReferenceValue != UNRESOLVED_EXPRESSION) {
            return directReferenceValue;
        }

        Object literalValue = evaluateLiteralValue(normalizedExpression);
        if (literalValue != UNRESOLVED_EXPRESSION) {
            return literalValue;
        }

        Object conditionalValue = evaluateConditionalExpression(
            normalizedExpression,
            localValues,
            variableValues,
            sourceName,
            fieldName,
            visitedLocals,
            visitedVariables
        );
        if (conditionalValue != UNRESOLVED_EXPRESSION) {
            return conditionalValue;
        }

        Object logicalValue = evaluateLogicalExpression(
            normalizedExpression,
            localValues,
            variableValues,
            sourceName,
            fieldName,
            visitedLocals,
            visitedVariables
        );
        if (logicalValue != UNRESOLVED_EXPRESSION) {
            return logicalValue;
        }

        Object comparisonValue = evaluateComparisonExpression(
            normalizedExpression,
            localValues,
            variableValues,
            sourceName,
            fieldName,
            visitedLocals,
            visitedVariables
        );
        if (comparisonValue != UNRESOLVED_EXPRESSION) {
            return comparisonValue;
        }

        Object functionValue = evaluateFunctionExpression(
            normalizedExpression,
            localValues,
            variableValues,
            sourceName,
            fieldName,
            visitedLocals,
            visitedVariables
        );
        if (functionValue != UNRESOLVED_EXPRESSION) {
            return functionValue;
        }

        if (looksLikeUnsupportedExpression(normalizedExpression)) {
            return null;
        }

        return normalizedExpression;
    }

    private static final Object UNRESOLVED_EXPRESSION = new Object();

    private static Object evaluateDirectReference(
        String expression,
        Map<String, Object> localValues,
        Map<String, Object> variableValues,
        String sourceName,
        String fieldName,
        LinkedHashSet<String> visitedLocals,
        LinkedHashSet<String> visitedVariables
    ) {
        if (isSimpleReference(expression, "local.")) {
            return evaluateLocalReference(expression, localValues, variableValues, sourceName, fieldName, visitedLocals, visitedVariables);
        }
        if (isSimpleReference(expression, "var.")) {
            return evaluateVariableReference(expression, localValues, variableValues, sourceName, fieldName, visitedLocals, visitedVariables);
        }
        return UNRESOLVED_EXPRESSION;
    }

    private static Object evaluateLocalReference(
        String expression,
        Map<String, Object> localValues,
        Map<String, Object> variableValues,
        String sourceName,
        String fieldName,
        LinkedHashSet<String> visitedLocals,
        LinkedHashSet<String> visitedVariables
    ) {
        String localName = expression.substring("local.".length());
        if (!visitedLocals.add(localName)) {
            return null;
        }
        Object localValue = localValues.get(localName);
        return resolveObjectValue(localValue, localValues, variableValues, sourceName, fieldName, visitedLocals, visitedVariables);
    }

    private static Object evaluateVariableReference(
        String expression,
        Map<String, Object> localValues,
        Map<String, Object> variableValues,
        String sourceName,
        String fieldName,
        LinkedHashSet<String> visitedLocals,
        LinkedHashSet<String> visitedVariables
    ) {
        String variableName = expression.substring("var.".length());
        if (!visitedVariables.add(variableName)) {
            return null;
        }
        Object variableValue = variableValues.get(variableName);
        return resolveObjectValue(variableValue, localValues, variableValues, sourceName, fieldName, visitedLocals, visitedVariables);
    }

    private static Object evaluateLiteralValue(String expression) {
        if ("null".equals(expression)) {
            return null;
        }
        if ("true".equalsIgnoreCase(expression)) {
            return true;
        }
        if ("false".equalsIgnoreCase(expression)) {
            return false;
        }
        Integer literalInteger = parseLiteralIntegerHelper(expression);
        return literalInteger == null ? UNRESOLVED_EXPRESSION : literalInteger;
    }

    private static Object evaluateConditionalExpression(
        String expression,
        Map<String, Object> localValues,
        Map<String, Object> variableValues,
        String sourceName,
        String fieldName,
        LinkedHashSet<String> visitedLocals,
        LinkedHashSet<String> visitedVariables
    ) {
        ConditionalParts conditionalParts = findTopLevelConditional(expression);
        if (conditionalParts == null) {
            return UNRESOLVED_EXPRESSION;
        }
        boolean conditionValue = coerceBoolean(
            evaluateExpression(conditionalParts.condition, localValues, variableValues, sourceName, fieldName, visitedLocals, visitedVariables)
        );
        return evaluateExpression(
            conditionValue ? conditionalParts.whenTrue : conditionalParts.whenFalse,
            localValues,
            variableValues,
            sourceName,
            fieldName,
            visitedLocals,
            visitedVariables
        );
    }

    private static Object evaluateLogicalExpression(
        String expression,
        Map<String, Object> localValues,
        Map<String, Object> variableValues,
        String sourceName,
        String fieldName,
        LinkedHashSet<String> visitedLocals,
        LinkedHashSet<String> visitedVariables
    ) {
        Object logicalAndValue = evaluateBinaryLogicalExpression(
            expression, "&&", localValues, variableValues, sourceName, fieldName, visitedLocals, visitedVariables
        );
        if (logicalAndValue != UNRESOLVED_EXPRESSION) {
            return logicalAndValue;
        }
        return evaluateBinaryLogicalExpression(
            expression, "||", localValues, variableValues, sourceName, fieldName, visitedLocals, visitedVariables
        );
    }

    private static Object evaluateBinaryLogicalExpression(
        String expression,
        String operator,
        Map<String, Object> localValues,
        Map<String, Object> variableValues,
        String sourceName,
        String fieldName,
        LinkedHashSet<String> visitedLocals,
        LinkedHashSet<String> visitedVariables
    ) {
        BinaryParts binaryParts = splitTopLevel(expression, operator);
        if (binaryParts == null) {
            return UNRESOLVED_EXPRESSION;
        }
        boolean left = coerceBoolean(evaluateExpression(binaryParts.left, localValues, variableValues, sourceName, fieldName, visitedLocals, visitedVariables));
        boolean right = coerceBoolean(evaluateExpression(binaryParts.right, localValues, variableValues, sourceName, fieldName, visitedLocals, visitedVariables));
        return "&&".equals(operator) ? left && right : left || right;
    }

    private static Object evaluateComparisonExpression(
        String expression,
        Map<String, Object> localValues,
        Map<String, Object> variableValues,
        String sourceName,
        String fieldName,
        LinkedHashSet<String> visitedLocals,
        LinkedHashSet<String> visitedVariables
    ) {
        for (String operator : List.of("!=", "==", ">=", "<=", ">", "<")) {
            BinaryParts comparison = splitTopLevel(expression, operator);
            if (comparison == null) {
                continue;
            }
            Object left = evaluateExpression(comparison.left, localValues, variableValues, sourceName, fieldName, visitedLocals, visitedVariables);
            Object right = evaluateExpression(comparison.right, localValues, variableValues, sourceName, fieldName, visitedLocals, visitedVariables);
            return compareValues(left, right, operator);
        }
        return UNRESOLVED_EXPRESSION;
    }

    private static Object evaluateFunctionExpression(
        String expression,
        Map<String, Object> localValues,
        Map<String, Object> variableValues,
        String sourceName,
        String fieldName,
        LinkedHashSet<String> visitedLocals,
        LinkedHashSet<String> visitedVariables
    ) {
        Object lengthValue = evaluateLengthExpression(
            expression, localValues, variableValues, sourceName, fieldName, visitedLocals, visitedVariables
        );
        if (lengthValue != UNRESOLVED_EXPRESSION) {
            return lengthValue;
        }
        return evaluateContainsExpression(
            expression, localValues, variableValues, sourceName, fieldName, visitedLocals, visitedVariables
        );
    }

    private static Object evaluateLengthExpression(
        String expression,
        Map<String, Object> localValues,
        Map<String, Object> variableValues,
        String sourceName,
        String fieldName,
        LinkedHashSet<String> visitedLocals,
        LinkedHashSet<String> visitedVariables
    ) {
        if (!expression.startsWith("length(") || !expression.endsWith(")")) {
            return UNRESOLVED_EXPRESSION;
        }
        Object value = evaluateExpression(
            expression.substring("length(".length(), expression.length() - 1),
            localValues,
            variableValues,
            sourceName,
            fieldName,
            visitedLocals,
            visitedVariables
        );
        if (value instanceof String string) {
            return string.length();
        }
        if (value instanceof List<?> list) {
            return list.size();
        }
        if (value instanceof Map<?, ?> map) {
            return map.size();
        }
        return null;
    }

    private static Object evaluateContainsExpression(
        String expression,
        Map<String, Object> localValues,
        Map<String, Object> variableValues,
        String sourceName,
        String fieldName,
        LinkedHashSet<String> visitedLocals,
        LinkedHashSet<String> visitedVariables
    ) {
        if (!expression.startsWith("contains(") || !expression.endsWith(")")) {
            return UNRESOLVED_EXPRESSION;
        }
        int commaIndex = findTopLevelComma(expression, "contains(".length(), expression.length() - 1);
        if (commaIndex < 0) {
            return null;
        }
        Object container = evaluateExpression(
            expression.substring("contains(".length(), commaIndex),
            localValues,
            variableValues,
            sourceName,
            fieldName,
            visitedLocals,
            visitedVariables
        );
        Object candidate = evaluateExpression(
            expression.substring(commaIndex + 1, expression.length() - 1),
            localValues,
            variableValues,
            sourceName,
            fieldName,
            visitedLocals,
            visitedVariables
        );
        if (container instanceof List<?> list) {
            return list.contains(candidate);
        }
        if (container instanceof String string && candidate != null) {
            return string.contains(String.valueOf(candidate));
        }
        return null;
    }

    private static boolean compareValues(Object left, Object right, String operator) {
        if ("==".equals(operator)) {
            return Objects.equals(left, right);
        }
        if ("!=".equals(operator)) {
            return !Objects.equals(left, right);
        }

        Integer leftInteger = coerceInteger(left);
        Integer rightInteger = coerceInteger(right);
        if (leftInteger == null || rightInteger == null) {
            return false;
        }
        return switch (operator) {
            case ">" -> leftInteger > rightInteger;
            case "<" -> leftInteger < rightInteger;
            case ">=" -> leftInteger >= rightInteger;
            case "<=" -> leftInteger <= rightInteger;
            default -> false;
        };
    }

    private static Integer coerceInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Integer integer) {
            return integer;
        }
        if (value instanceof Number number) {
            return (int) Math.round(number.doubleValue());
        }
        if (value instanceof String string) {
            String trimmed = string.trim();
            Integer literalInteger = parseLiteralIntegerHelper(trimmed);
            if (literalInteger != null) {
                return literalInteger;
            }
            try {
                double decimalValue = Double.parseDouble(trimmed);
                return (int) Math.round(decimalValue);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean coerceBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String string) {
            String trimmed = string.trim();
            if ("true".equalsIgnoreCase(trimmed)) {
                return true;
            }
            if ("false".equalsIgnoreCase(trimmed)) {
                return false;
            }
        }
        return false;
    }

    private static boolean isSimpleReference(String expression, String prefix) {
        if (!expression.startsWith(prefix)) {
            return false;
        }
        return expression.indexOf(' ') < 0
            && expression.indexOf('(') < 0
            && expression.indexOf(')') < 0
            && expression.indexOf('[') < 0
            && expression.indexOf(']') < 0
            && expression.indexOf('?') < 0
            && expression.indexOf(':') < 0
            && expression.indexOf('=') < 0
            && expression.indexOf('&') < 0
            && expression.indexOf('|') < 0
            && expression.indexOf('>') < 0
            && expression.indexOf('<') < 0
            && expression.indexOf(',') < 0;
    }

    private static String stripOuterParentheses(String expression) {
        String normalized = expression == null ? null : expression.trim();
        if (normalized == null || normalized.length() < 2 || normalized.charAt(0) != '(' || normalized.charAt(normalized.length() - 1) != ')') {
            return normalized;
        }
        int depth = 0;
        for (int index = 0; index < normalized.length(); index++) {
            char current = normalized.charAt(index);
            if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
                if (depth == 0 && index < normalized.length() - 1) {
                    return normalized;
                }
            }
        }
        return normalized.substring(1, normalized.length() - 1).trim();
    }

    private static ConditionalParts findTopLevelConditional(String expression) {
        int questionIndex = findTopLevelSymbol(expression, '?', 0);
        if (questionIndex < 0) {
            return null;
        }
        int colonIndex = findTopLevelSymbol(expression, ':', questionIndex + 1);
        if (colonIndex < 0) {
            return null;
        }
        return new ConditionalParts(
            expression.substring(0, questionIndex).trim(),
            expression.substring(questionIndex + 1, colonIndex).trim(),
            expression.substring(colonIndex + 1).trim()
        );
    }

    private static int findTopLevelSymbol(String expression, char target, int startInclusive) {
        int depthRound = 0;
        int depthSquare = 0;
        for (int index = startInclusive; index < expression.length(); index++) {
            char current = expression.charAt(index);
            depthRound = adjustRoundDepth(current, depthRound);
            depthSquare = adjustSquareDepth(current, depthSquare);
            if (current == target && depthRound == 0 && depthSquare == 0) {
                return index;
            }
        }
        return -1;
    }

    private static int adjustRoundDepth(char current, int depthRound) {
        if (current == '(') {
            return depthRound + 1;
        }
        if (current == ')') {
            return depthRound - 1;
        }
        return depthRound;
    }

    private static int adjustSquareDepth(char current, int depthSquare) {
        if (current == '[') {
            return depthSquare + 1;
        }
        if (current == ']') {
            return depthSquare - 1;
        }
        return depthSquare;
    }

    private static BinaryParts splitTopLevel(String expression, String operator) {
        int depthRound = 0;
        int depthSquare = 0;
        for (int index = 0; index <= expression.length() - operator.length(); index++) {
            char current = expression.charAt(index);
            if (current == '(') {
                depthRound++;
                continue;
            }
            if (current == ')') {
                depthRound--;
                continue;
            }
            if (current == '[') {
                depthSquare++;
                continue;
            }
            if (current == ']') {
                depthSquare--;
                continue;
            }
            if (depthRound == 0 && depthSquare == 0 && expression.startsWith(operator, index)) {
                return new BinaryParts(
                    expression.substring(0, index).trim(),
                    expression.substring(index + operator.length()).trim()
                );
            }
        }
        return null;
    }

    private static int findTopLevelComma(String expression, int startInclusive, int endExclusive) {
        int depthRound = 0;
        int depthSquare = 0;
        for (int index = startInclusive; index < endExclusive; index++) {
            char current = expression.charAt(index);
            if (current == '(') {
                depthRound++;
            } else if (current == ')') {
                depthRound--;
            } else if (current == '[') {
                depthSquare++;
            } else if (current == ']') {
                depthSquare--;
            } else if (current == ',' && depthRound == 0 && depthSquare == 0) {
                return index;
            }
        }
        return -1;
    }

    private static boolean looksLikeUnsupportedExpression(String expression) {
        String normalized = expression == null ? null : expression.trim();
        if (normalized == null || normalized.isEmpty()) {
            return false;
        }
        return normalized.startsWith("data.")
            || normalized.startsWith("module.")
            || normalized.contains("count.index")
            || normalized.contains("each.")
            || normalized.contains(" for ")
            || normalized.startsWith("lookup(")
            || normalized.startsWith("format(")
            || normalized.startsWith("keys(")
            || normalized.startsWith("values(")
            || normalized.startsWith("slice(")
            || normalized.startsWith("setintersection(")
            || normalized.startsWith("tolist(")
            || normalized.contains("[")
            || normalized.contains("]");
    }

    private static Integer parseLiteralIntegerHelper(String expression) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
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
        if (normalized.length() >= 2
            && normalized.startsWith("\"")
            && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized.isEmpty() ? null : normalized;
    }

    private record ConditionalParts(String condition, String whenTrue, String whenFalse) {
    }

    private record BinaryParts(String left, String right) {
    }
}
