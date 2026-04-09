package com.sailv.terraform.analysis.infrastructure.parser;

import com.bertramlabs.plugins.hcl4j.HCLParser;
import com.bertramlabs.plugins.hcl4j.HCLParserException;
import com.sailv.terraform.analysis.application.parser.TerraformFileParser;
import com.sailv.terraform.analysis.domain.model.ProviderType;
import com.sailv.terraform.analysis.domain.model.TerraformAction;
import com.sailv.terraform.analysis.domain.model.TerraformModuleCall;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * `.tf` 解析器。
 *
 * <p>当前要求下 `.tf` 只使用 hcl4j 做主解析。
 * parser 只提取：
 * <ul>
 *     <li>provider block 名称</li>
 *     <li>locals 中可以稳定识别为“字面量 / local.xxx”的值</li>
 *     <li>resource / data 动作</li>
 *     <li>resource 上的原始 `count` / `flavor_id` / `system_disk_size` / `size` 表达式</li>
 * </ul>
 *
 * <p>这些表达式的最终求值不在 parser 中做，
 * 而是在 service 层把同目录所有文件汇总后，只按“字面量 / local.xxx”规则计算。
 *
 * <p>这里之所以不用普通 `HashMap` / `HashSet`，而统一使用 `LinkedHashMap` / `LinkedHashSet`，
 * 是为了让调试结果和日志顺序稳定。zip 内文件很多时，稳定顺序对排查“哪个 locals 先被收集，
 * 哪个资源后来被聚合”很关键。
 *
 * <p>另外要注意：这里虽然会在 hcl4j 漏掉字段时，使用原始文本把顶层属性补回来，
 * 但这不是“自研 HCL 解析器”。hcl4j 仍然负责主解析；文本补位只处理已被识别出的资源块，
 * 且只补顶层的少数字段，避免因为 hcl4j 在复杂表达式上的兼容差异导致这些关键字段丢失。
 */
@Log4j2
@Component
public class HclTerraformFileParser implements TerraformFileParser {

    @Override
    public boolean supports(String fileName) {
        String normalized = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return normalized.endsWith(".tf") && !normalized.endsWith(".tf.json");
    }

    @Override
    public ParseResult parse(InputStream inputStream, String fileName) throws IOException {
        byte[] content = inputStream.readAllBytes();
        try {
            return parseWithHcl4j(content, fileName);
        } catch (HCLParserException | RuntimeException exception) {
            log.error("Skip Terraform file because hcl4j could not parse it. file={}, reason={}",
                fileName, rootMessage(exception));
            return emptyResult();
        }
    }

    private ParseResult parseWithHcl4j(byte[] content, String fileName) throws HCLParserException, IOException {
        Map<String, Object> root = asObject(new HCLParser().parse(new java.io.ByteArrayInputStream(content)));
        String rawContent = new String(content, StandardCharsets.UTF_8);

        Set<String> providerBlockNames = new LinkedHashSet<>();
        Map<String, Object> localValues = new LinkedHashMap<>();
        Map<String, Object> variableDefaults = new LinkedHashMap<>();
        List<TerraformModuleCall> moduleCalls = new ArrayList<>();
        List<TerraformAction> actions = new ArrayList<>();

        collectProviderBlocks(root.get("provider"), providerBlockNames);
        // hcl4j 在不同 Terraform 片段上，locals 根节点既可能表现为 `locals`，
        // 也可能表现为 `local`。这里同时兼容，避免 locals-only 文件在 zip 聚合时丢值。
        collectLocals(root.get("locals"), localValues);
        collectLocals(root.get("local"), localValues);
        collectVariables(root.get("variable"), variableDefaults);
        collectModuleCalls(root.get("module"), moduleCalls);
        collectActions(root.get("resource"), ProviderType.RESOURCE, fileName, actions);
        collectActions(root.get("data"), ProviderType.DATA, fileName, actions);
        supplementMissingTopLevelExpressions(rawContent, actions);

        return new ParseResult()
            .setProviderBlockNames(providerBlockNames)
            .setLocalValues(localValues)
            .setVariableDefaults(variableDefaults)
            .setModuleCalls(moduleCalls)
            .setActions(actions);
    }

    private ParseResult emptyResult() {
        return new ParseResult();
    }

    /**
     * hcl4j 在部分真实 Terraform 模板上会把顶层属性解析成 null，
     * 尤其是这些属性依赖复杂 locals/vars 时更容易出现。
     *
     * <p>这里不替代 hcl4j 的主解析职责，只在 hcl4j 已经识别出资源块的前提下，
     * 用原始文本把缺失的少数字段补回来。补位范围保持最小，只处理资源块顶层的：
     * `count`、`flavor_id`、`system_disk_size`、`size`、`mode`。
     */
    private void supplementMissingTopLevelExpressions(String rawContent, List<TerraformAction> actions) {
        if (rawContent == null || rawContent.isBlank() || actions == null || actions.isEmpty()) {
            return;
        }
        for (TerraformAction action : actions) {
            if (action == null
                || action.getProviderType() != ProviderType.RESOURCE
                || action.getProviderName() == null
                || action.getBlockName() == null) {
                continue;
            }

            String blockBody = findResourceBlockBody(rawContent, action.getProviderName(), action.getBlockName());
            if (blockBody == null) {
                continue;
            }

            overrideExpressionFromSource(action::setRequestedAmountExpression, blockBody, "count");
            overrideExpressionFromSource(action::setFlavorIdExpression, blockBody, "flavor_id");
            overrideExpressionFromSource(action::setSystemDiskSizeExpression, blockBody, "system_disk_size");
            overrideExpressionFromSource(action::setVolumeSizeExpression, blockBody, "size");
            overrideExpressionFromSource(action::setModeExpression, blockBody, "mode");
        }
    }

    private void overrideExpressionFromSource(
        java.util.function.Consumer<String> setter,
        String blockBody,
        String attributeName
    ) {
        String expression = findTopLevelAttributeExpression(blockBody, attributeName);
        if (expression != null) {
            setter.accept(normalizeExpression(expression));
        }
    }

    private String findResourceBlockBody(String content, String providerName, String blockName) {
        String resourceHeader = "resource \"" + providerName + "\" \"" + blockName + "\"";
        int searchFrom = 0;
        while (true) {
            int headerIndex = content.indexOf(resourceHeader, searchFrom);
            if (headerIndex < 0) {
                return null;
            }

            int braceIndex = content.indexOf('{', headerIndex + resourceHeader.length());
            if (braceIndex < 0) {
                return null;
            }

            int blockEnd = findMatchingBrace(content, braceIndex);
            if (blockEnd < 0) {
                return null;
            }
            return content.substring(braceIndex + 1, blockEnd);
        }
    }

    private int findMatchingBrace(String content, int openingBraceIndex) {
        int depth = 0;
        boolean inDoubleQuotedString = false;
        boolean inSingleQuotedString = false;
        boolean escaping = false;

        for (int index = openingBraceIndex; index < content.length(); index++) {
            char current = content.charAt(index);

            if (escaping) {
                escaping = false;
                continue;
            }

            if ((inDoubleQuotedString || inSingleQuotedString) && current == '\\') {
                escaping = true;
                continue;
            }

            if (!inSingleQuotedString && current == '"') {
                inDoubleQuotedString = !inDoubleQuotedString;
                continue;
            }
            if (!inDoubleQuotedString && current == '\'') {
                inSingleQuotedString = !inSingleQuotedString;
                continue;
            }
            if (inDoubleQuotedString || inSingleQuotedString) {
                continue;
            }

            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    private String findTopLevelAttributeExpression(String blockBody, String attributeName) {
        String[] lines = blockBody.split("\\R");
        int nestedDepth = 0;

        for (String rawLine : lines) {
            String line = stripLineComment(rawLine).trim();
            if (line.isEmpty()) {
                nestedDepth += braceDelta(rawLine);
                continue;
            }

            if (nestedDepth == 0 && line.startsWith(attributeName)) {
                int assignmentIndex = line.indexOf('=');
                if (assignmentIndex >= 0) {
                    return line.substring(assignmentIndex + 1).trim();
                }
            }

            nestedDepth += braceDelta(rawLine);
        }
        return null;
    }

    private String stripLineComment(String rawLine) {
        int hashIndex = rawLine.indexOf('#');
        int slashCommentIndex = rawLine.indexOf("//");
        int cutIndex = -1;
        if (hashIndex >= 0) {
            cutIndex = hashIndex;
        }
        if (slashCommentIndex >= 0) {
            cutIndex = cutIndex < 0 ? slashCommentIndex : Math.min(cutIndex, slashCommentIndex);
        }
        return cutIndex < 0 ? rawLine : rawLine.substring(0, cutIndex);
    }

    private int braceDelta(String rawLine) {
        int delta = 0;
        boolean inDoubleQuotedString = false;
        boolean inSingleQuotedString = false;
        boolean escaping = false;

        for (int index = 0; index < rawLine.length(); index++) {
            char current = rawLine.charAt(index);
            if (escaping) {
                escaping = false;
                continue;
            }
            if ((inDoubleQuotedString || inSingleQuotedString) && current == '\\') {
                escaping = true;
                continue;
            }
            if (!inSingleQuotedString && current == '"') {
                inDoubleQuotedString = !inDoubleQuotedString;
                continue;
            }
            if (!inDoubleQuotedString && current == '\'') {
                inSingleQuotedString = !inSingleQuotedString;
                continue;
            }
            if (inDoubleQuotedString || inSingleQuotedString) {
                continue;
            }
            if (current == '{') {
                delta++;
            } else if (current == '}') {
                delta--;
            }
        }
        return delta;
    }

    private void collectProviderBlocks(Object providerNode, Set<String> providerBlockNames) {
        providerBlockNames.addAll(asObject(providerNode).keySet());
    }

    private void collectLocals(Object localsNode, Map<String, Object> localValues) {
        if (localsNode instanceof List<?> localsList) {
            localsList.forEach(item -> collectLocals(item, localValues));
            return;
        }

        for (Map.Entry<String, Object> entry : asObject(localsNode).entrySet()) {
            Object value = asStructuredValue(entry.getValue());
            if (value != null) {
                localValues.put(entry.getKey(), value);
            }
        }
    }

    private void collectVariables(Object variablesNode, Map<String, Object> variableDefaults) {
        for (Map.Entry<String, Object> entry : asObject(variablesNode).entrySet()) {
            Map<String, Object> variableAttributes = asObject(entry.getValue());
            if (variableAttributes.isEmpty() && entry.getValue() instanceof List<?> variableList && !variableList.isEmpty()) {
                variableAttributes = asObject(variableList.getFirst());
            }
            if (variableAttributes.isEmpty()) {
                continue;
            }
            Object defaultValue = variableAttributes.get("default");
            if (defaultValue != null) {
                variableDefaults.put(entry.getKey(), asStructuredValue(defaultValue));
            }
        }
    }

    private void collectModuleCalls(Object moduleNode, List<TerraformModuleCall> moduleCalls) {
        for (Map.Entry<String, Object> entry : asObject(moduleNode).entrySet()) {
            String moduleName = normalize(entry.getKey());
            if (moduleName == null) {
                continue;
            }

            Map<String, Object> attributes = asObject(entry.getValue());
            if (attributes.isEmpty() && entry.getValue() instanceof List<?> moduleDefinitions && !moduleDefinitions.isEmpty()) {
                attributes = asObject(moduleDefinitions.getFirst());
            }
            if (attributes.isEmpty()) {
                continue;
            }

            TerraformModuleCall moduleCall = new TerraformModuleCall()
                .setModuleName(moduleName)
                .setSource(normalizeExpression(attributes.get("source")));
            Map<String, Object> inputValues = new LinkedHashMap<>();
            attributes.forEach((attributeName, rawValue) -> {
                if (!"source".equals(attributeName)) {
                    inputValues.put(attributeName, asStructuredValue(rawValue));
                }
            });
            moduleCall.setInputValues(inputValues);
            moduleCalls.add(moduleCall);
        }
    }

    private void collectActions(
        Object sectionNode,
        ProviderType providerType,
        String fileName,
        List<TerraformAction> actions
    ) {
        for (Map.Entry<String, Object> actionEntry : asObject(sectionNode).entrySet()) {
            String terraformType = normalize(actionEntry.getKey());
            if (terraformType == null) {
                log.info("Provider name could not be resolved from Terraform action. action={}, file={}",
                    actionEntry.getKey(), fileName);
                continue;
            }
            collectActionInstances(terraformType, actionEntry.getValue(), providerType, actions);
        }
    }

    private void collectActionInstances(
        String terraformType,
        Object actionNode,
        ProviderType providerType,
        List<TerraformAction> actions
    ) {
        if (actionNode instanceof List<?> actionNodes) {
            actionNodes.forEach(node -> collectActionInstances(terraformType, node, providerType, actions));
            return;
        }

        Map<String, Object> instances = asObject(actionNode);
        if (instances.isEmpty()) {
            actions.add(new TerraformAction()
                .setProviderName(terraformType)
                .setBlockName(terraformType)
                .setProviderType(providerType)
                .setRequestedAmount(1));
            return;
        }

        for (Map.Entry<String, Object> instanceEntry : instances.entrySet()) {
            String blockName = normalize(instanceEntry.getKey());
            if (blockName == null) {
                blockName = terraformType;
            }
            Map<String, Object> attributes = asObject(instanceEntry.getValue());
            TerraformAction action = new TerraformAction()
                .setProviderName(terraformType)
                .setBlockName(blockName)
                .setProviderType(providerType)
                .setRequestedAmount(providerType == ProviderType.DATA ? 1 : 0)
                .setRequestedAmountExpression(providerType == ProviderType.RESOURCE
                    ? normalizeExpression(attributes.get("count"))
                    : null)
                .setFlavorIdExpression(providerType == ProviderType.RESOURCE
                    ? normalizeExpression(attributes.get("flavor_id"))
                    : null)
                .setSystemDiskSizeExpression(providerType == ProviderType.RESOURCE
                    ? normalizeExpression(attributes.get("system_disk_size"))
                    : null)
                .setVolumeSizeExpression(providerType == ProviderType.RESOURCE
                    ? normalizeExpression(attributes.get("size"))
                    : null)
                .setModeExpression(providerType == ProviderType.RESOURCE
                    ? normalizeExpression(attributes.get("mode"))
                    : null);
            actions.add(action);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asObject(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private Object asStructuredValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            map.forEach((key, rawChild) -> {
                if (key != null) {
                    resolved.put(String.valueOf(key), asStructuredValue(rawChild));
                }
            });
            return resolved;
        }
        if (value instanceof List<?> list) {
            List<Object> resolved = new ArrayList<>();
            list.forEach(item -> resolved.add(asStructuredValue(item)));
            return resolved;
        }
        if (value instanceof Number number) {
            if (number.doubleValue() == Math.floor(number.doubleValue())) {
                return number.intValue();
            }
            return Double.parseDouble(stripTrailingZero(String.valueOf(number.doubleValue())));
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String string) {
            return normalizeExpression(string);
        }
        return normalizeExpression(value);
    }

    private String stripTrailingZero(String value) {
        if (value == null || !value.endsWith(".0")) {
            return value;
        }
        return value.substring(0, value.length() - 2);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeExpression(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        String string;
        if (value instanceof String rawString) {
            string = rawString;
        } else {
            string = String.valueOf(value);
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
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
