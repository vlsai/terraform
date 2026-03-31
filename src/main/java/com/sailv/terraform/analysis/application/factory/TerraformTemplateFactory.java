package com.sailv.terraform.analysis.application.factory;

import com.sailv.terraform.analysis.application.parser.TerraformFileParser;
import com.sailv.terraform.analysis.domain.model.TerraformAction;
import com.sailv.terraform.analysis.domain.model.TerraformTemplate;
import com.sailv.terraform.analysis.infrastructure.parser.HclTerraformFileParser;
import com.sailv.terraform.analysis.infrastructure.parser.JsonTerraformFileParser;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * TerraformTemplate 工厂。
 *
 * <p>负责把上传的 `InputStream + fileName` 解析成领域聚合 {@link TerraformTemplate}。
 * 这里处理的都是应用层编排细节：
 * <ul>
 *     <li>识别上传文件类型</li>
 *     <li>顺序扫描 zip 中的每个 Terraform 文件 entry</li>
 *     <li>按目录聚合同一个 module 的 locals 和 actions</li>
 *     <li>仅按“字面量 / local.xxx”规则求值数量与规格字段</li>
 * </ul>
 *
 * <p>解析完成后，只把归一化后的动作集合放进领域对象；
 * 领域对象本身不再关心 zip、parser 选择或 locals 聚合细节。
 */
@Log4j2
public class TerraformTemplateFactory {

    private final List<TerraformFileParser> parsers;

    public TerraformTemplateFactory() {
        this(List.of(new JsonTerraformFileParser(), new HclTerraformFileParser()));
    }

    public TerraformTemplateFactory(List<TerraformFileParser> parsers) {
        this.parsers = List.copyOf(parsers);
    }

    public TerraformTemplate create(InputStream inputStream, String fileName) throws IOException {
        Objects.requireNonNull(inputStream, "inputStream cannot be null");
        Objects.requireNonNull(fileName, "fileName cannot be null");

        if (isZipFile(fileName)) {
            return parseZip(inputStream);
        }
        if (isTerraformFile(fileName)) {
            return parseSingleFile(inputStream, fileName);
        }
        throw new IllegalArgumentException("Unsupported template source: " + fileName);
    }

    private TerraformTemplate parseZip(InputStream inputStream) throws IOException {
        Map<String, ModuleAggregation> moduleAggregations = new LinkedHashMap<>();
        try (ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }

                String entryName = normalizeArchivePath(entry.getName());
                if (!isTerraformFile(entryName)) {
                    continue;
                }

                byte[] content = zipInputStream.readAllBytes();
                TerraformFileParser.ParseResult parseResult = parserFor(entryName)
                    .parse(new ByteArrayInputStream(content), entryName);

                moduleAggregations.computeIfAbsent(parentDirectory(entryName), ignored -> new ModuleAggregation())
                    .merge(parseResult, entryName);
            }
        }

        if (moduleAggregations.isEmpty()) {
            log.warn("No Terraform files were found in the uploaded zip source.");
            return new TerraformTemplate(TerraformTemplate.SourceType.ZIP, List.of());
        }

        List<TerraformAction> resolvedActions = new ArrayList<>();
        moduleAggregations.forEach((moduleDir, moduleAggregation) -> {
            log.debug("Aggregated Terraform module. dir={}, locals={}, actionExpressions={}",
                moduleDir,
                moduleAggregation.localValues,
                moduleAggregation.actions.stream()
                    .map(action -> action.action.getProviderName() + ":" + action.action.getRequestedAmountExpression())
                    .toList());
            resolvedActions.addAll(moduleAggregation.resolveActions());
        });

        return new TerraformTemplate(TerraformTemplate.SourceType.ZIP, resolvedActions);
    }

    private TerraformTemplate parseSingleFile(InputStream inputStream, String fileName) throws IOException {
        TerraformFileParser.ParseResult parseResult = parserFor(fileName).parse(inputStream, fileName);
        ModuleAggregation moduleAggregation = new ModuleAggregation();
        moduleAggregation.merge(parseResult, fileName);
        return new TerraformTemplate(resolveSourceType(fileName), moduleAggregation.resolveActions());
    }

    private TerraformFileParser parserFor(String fileName) {
        return parsers.stream()
            .filter(parser -> parser.supports(fileName))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No parser available for file " + fileName));
    }

    private TerraformTemplate.SourceType resolveSourceType(String fileName) {
        String normalized = fileName.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".tf.json")) {
            return TerraformTemplate.SourceType.TF_JSON;
        }
        if (normalized.endsWith(".tf")) {
            return TerraformTemplate.SourceType.TF;
        }
        if (normalized.endsWith(".zip")) {
            return TerraformTemplate.SourceType.ZIP;
        }
        throw new IllegalArgumentException("Unsupported template source: " + fileName);
    }

    private boolean isZipFile(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    private boolean isTerraformFile(String fileName) {
        String normalized = fileName.toLowerCase(Locale.ROOT);
        return normalized.endsWith(".tf") || normalized.endsWith(".tf.json");
    }

    private String parentDirectory(String fileName) {
        int lastSlash = fileName.lastIndexOf('/');
        if (lastSlash < 0) {
            return "";
        }
        return fileName.substring(0, lastSlash);
    }

    private String normalizeArchivePath(String rawEntryName) {
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

    private int resolveRequestedAmount(String expression, Map<String, String> localValues, String sourceName) {
        Integer resolved = resolveIntegerExpression(expression, localValues, sourceName, "count");
        return resolved == null ? 1 : Math.max(resolved, 0);
    }

    private Integer resolveIntegerExpression(
        String expression,
        Map<String, String> localValues,
        String sourceName,
        String fieldName
    ) {
        String resolvedValue = resolveStringExpression(expression, localValues, sourceName, fieldName, new LinkedHashSet<>());
        if (resolvedValue == null) {
            return null;
        }
        Integer literal = parseLiteralInteger(resolvedValue);
        if (literal != null) {
            return literal;
        }
        log.info("Unsupported integer expression. field={}, expression={}, source={}",
            fieldName, expression, sourceName);
        return null;
    }

    private String resolveStringExpression(
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
                log.warn("Detected local reference cycle. localName={}, source={}",
                    localName, sourceName);
                return null;
            }

            String localValue = localValues.get(localName);
            if (localValue == null) {
                log.warn("Local value not found while resolving expression. field={}, expression={}, source={}",
                    fieldName, normalizedExpression, sourceName);
                return null;
            }
            return resolveStringExpression(localValue, localValues, sourceName, fieldName, visitedLocals);
        }

        if (looksLikeUnsupportedExpression(normalizedExpression)) {
            log.info("Unsupported expression. field={}, expression={}, source={}",
                fieldName,
                normalizedExpression,
                sourceName);
            return null;
        }

        return normalizedExpression;
    }

    private boolean looksLikeUnsupportedExpression(String expression) {
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

    private Integer parseLiteralInteger(String expression) {
        if (!expression.chars().allMatch(character -> Character.isDigit(character) || character == '-')) {
            return null;
        }
        try {
            return Integer.parseInt(expression);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String normalizeExpression(String expression) {
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

    private final class ModuleAggregation {
        private final Map<String, String> localValues = new LinkedHashMap<>();
        private final List<ActionWithSource> actions = new ArrayList<>();

        private void merge(TerraformFileParser.ParseResult parseResult, String sourceName) {
            if (parseResult == null) {
                return;
            }
            if (parseResult.getLocalValues() != null) {
                parseResult.getLocalValues().forEach(localValues::put);
            }
            if (parseResult.getActions() != null) {
                parseResult.getActions().forEach(action -> actions.add(new ActionWithSource(action, sourceName)));
            }
        }

        private List<TerraformAction> resolveActions() {
            List<TerraformAction> resolved = new ArrayList<>();
            for (ActionWithSource actionWithSource : actions) {
                TerraformAction action = actionWithSource.action;
                if (action == null) {
                    continue;
                }

                if (action.getProviderType() == TerraformAction.ProviderType.DATA_SOURCE) {
                    action.setRequestedAmount(1);
                    action.setRequestedAmountExpression(null);
                    resolved.add(action);
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
                resolved.add(action);
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
}
