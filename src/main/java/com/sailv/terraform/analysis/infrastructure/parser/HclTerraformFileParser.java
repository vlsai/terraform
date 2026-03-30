package com.sailv.terraform.analysis.infrastructure.parser;

import com.bertramlabs.plugins.hcl4j.HCLParser;
import com.bertramlabs.plugins.hcl4j.HCLParserException;
import com.sailv.terraform.analysis.application.parser.TerraformFileParser;
import com.sailv.terraform.analysis.domain.model.TerraformAction;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * `.tf` 解析器。
 *
 * <p>按当前要求，`.tf` 文件不再使用任何自研解释逻辑，
 * 只通过 `hcl4j` 读取 HCL 结构并提取业务需要的数据。
 *
 * <p>当前只关心四类信息：
 * <ul>
 *     <li>provider block 名称</li>
 *     <li>resource / data 的 Terraform 类型与实例名</li>
 *     <li>resource 对应的 count 数量</li>
 *     <li>module source</li>
 * </ul>
 *
 * <p>这里仍然保留 `locals` / `count` 的读取逻辑，但它们的数据来源完全是
 * `hcl4j` 解析后的对象树，而不是手工扫描源码。
 *
 * <p>如果 `hcl4j` 无法解析某个 `.tf` 文件，当前实现会记录一条摘要日志并跳过该文件，
 * 保证压缩包里其他可解析文件仍能继续参与分析。
 */
public class HclTerraformFileParser implements TerraformFileParser {

    private static final Logger LOGGER = Logger.getLogger(HclTerraformFileParser.class.getName());

    @Override
    public boolean supports(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".tf") && !name.endsWith(".tf.json");
    }

    @Override
    public ParseResult parse(Path file) throws IOException {
        try {
            return parseWithHcl4j(file);
        } catch (HCLParserException | RuntimeException exception) {
            LOGGER.warning(() -> "[HCL4J_PARSE_FAILED] Skip Terraform file because hcl4j could not parse it: "
                + file + ", reason=" + rootMessage(exception));
            return emptyResult();
        }
    }

    private ParseResult parseWithHcl4j(Path file) throws HCLParserException, IOException {
        Map<String, Object> root = asObject(new HCLParser().parse(file.toFile()));

        // LinkedHash* 的目的不是功能必须，而是让调试顺序稳定：
        // 去重的同时保留首次发现顺序，便于断点和日志排查。
        Set<String> providerBlockNames = new LinkedHashSet<>();
        List<TerraformAction> actions = new ArrayList<>();
        List<ModuleReference> moduleReferences = new ArrayList<>();
        Map<String, Integer> localValues = new LinkedHashMap<>();

        collectProviderBlocks(value(root, "provider"), providerBlockNames);
        collectLocals(value(root, "locals"), localValues);
        collectActions(value(root, "resource"), TerraformAction.Kind.RESOURCE, file, localValues, actions);
        collectActions(value(root, "data"), TerraformAction.Kind.DATA_SOURCE, file, localValues, actions);
        collectModules(file, value(root, "module"), moduleReferences);

        return new ParseResult(providerBlockNames, actions, moduleReferences);
    }

    private ParseResult emptyResult() {
        return new ParseResult(Set.of(), List.of(), List.of());
    }

    private void collectProviderBlocks(Object providerNode, Set<String> providerBlockNames) {
        providerBlockNames.addAll(asObject(providerNode).keySet());
    }

    private void collectLocals(Object localsNode, Map<String, Integer> localValues) {
        if (localsNode instanceof List<?> localsList) {
            localsList.forEach(item -> collectLocals(item, localValues));
            return;
        }

        // 这里只提取后续 count = local.xxx 会用到的整数 locals。
        // 非整数 locals、复杂表达式 locals 都不会参与数量计算。
        for (Map.Entry<String, Object> entry : asObject(localsNode).entrySet()) {
            Integer literal = asInteger(entry.getValue());
            if (literal != null) {
                localValues.put(entry.getKey(), literal);
            }
        }
    }

    private void collectActions(
        Object sectionNode,
        TerraformAction.Kind kind,
        Path file,
        Map<String, Integer> localValues,
        List<TerraformAction> actions
    ) {
        for (Map.Entry<String, Object> actionEntry : asObject(sectionNode).entrySet()) {
            String terraformType = normalize(actionEntry.getKey());
            if (terraformType == null) {
                LOGGER.warning(() -> "[PROVIDER_NAME_UNRESOLVED] Could not resolve provider name from action "
                    + actionEntry.getKey() + ": " + file);
                continue;
            }
            collectActionInstances(file, kind, terraformType, terraformType, actionEntry.getValue(), localValues, actions);
        }
    }

    private void collectActionInstances(
        Path file,
        TerraformAction.Kind kind,
        String providerName,
        String actionName,
        Object actionNode,
        Map<String, Integer> localValues,
        List<TerraformAction> actions
    ) {
        if (actionNode instanceof List<?> actionNodes) {
            actionNodes.forEach(node -> collectActionInstances(file, kind, providerName, actionName, node, localValues, actions));
            return;
        }

        Map<String, Object> instances = asObject(actionNode);
        if (instances.isEmpty()) {
            actions.add(new TerraformAction(providerName, actionName, actionName, kind, 1));
            return;
        }

        for (Map.Entry<String, Object> instanceEntry : instances.entrySet()) {
            int requestedAmount = kind == TerraformAction.Kind.RESOURCE
                ? resolveRequestedAmount(file, instanceEntry.getValue(), localValues)
                : 1;
            if (requestedAmount <= 0) {
                continue;
            }
            actions.add(new TerraformAction(
                providerName,
                actionName,
                instanceEntry.getKey(),
                kind,
                requestedAmount
            ));
        }
    }

    private int resolveRequestedAmount(Path file, Object instanceConfig, Map<String, Integer> localValues) {
        // 当前数量规则只支持：
        // 1. count = 2
        // 2. count = local.xxx
        // 其余表达式按默认值 1 处理。
        String expression = normalizeExpression(value(asObject(instanceConfig), "count"));
        if (expression == null) {
            return 1;
        }
        Integer literal = asInteger(expression);
        if (literal != null) {
            return Math.max(literal, 0);
        }
        if (expression.startsWith("local.")) {
            Integer resolved = localValues.get(expression.substring("local.".length()));
            if (resolved != null) {
                return Math.max(resolved, 0);
            }
            LOGGER.warning(() -> "[LOCAL_COUNT_NOT_FOUND] Could not resolve local count expression "
                + expression + ": " + file);
            return 1;
        }

        LOGGER.info(() -> "[UNSUPPORTED_COUNT_EXPRESSION] Unsupported Terraform count expression "
            + expression + ": " + file);
        return 1;
    }

    private void collectModules(Path file, Object moduleNode, List<ModuleReference> moduleReferences) {
        for (Object moduleConfig : asObject(moduleNode).values()) {
            extractModuleSource(file, moduleConfig, moduleReferences);
        }
    }

    private void extractModuleSource(Path file, Object moduleConfig, List<ModuleReference> moduleReferences) {
        if (moduleConfig instanceof List<?> modules) {
            modules.forEach(node -> extractModuleSource(file, node, moduleReferences));
            return;
        }
        String source = asString(value(asObject(moduleConfig), "source"));
        if (source != null && !source.isBlank()) {
            moduleReferences.add(new ModuleReference(source));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asObject(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private Object value(Map<String, Object> map, String key) {
        return map.get(key);
    }

    private String asString(Object value) {
        return value instanceof String string ? string : null;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string) {
            String normalized = normalizeExpression(string);
            if (normalized == null || normalized.isEmpty()) {
                return null;
            }
            if (!normalized.chars().allMatch(character -> Character.isDigit(character) || character == '-')) {
                return null;
            }
            try {
                return Integer.parseInt(normalized);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String normalizeExpression(Object value) {
        if (!(value instanceof String string)) {
            if (value instanceof Number number) {
                return String.valueOf(number.intValue());
            }
            return null;
        }
        String normalized = string.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.startsWith("${") && normalized.endsWith("}")) {
            normalized = normalized.substring(2, normalized.length() - 1).trim();
        }
        return normalized;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName() + ": " + current.getMessage();
    }
}
