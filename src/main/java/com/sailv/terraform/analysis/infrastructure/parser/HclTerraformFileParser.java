package com.sailv.terraform.analysis.infrastructure.parser;

import com.bertramlabs.plugins.hcl4j.HCLParser;
import com.bertramlabs.plugins.hcl4j.HCLParserException;
import com.sailv.terraform.analysis.application.parser.TerraformFileParser;
import com.sailv.terraform.analysis.domain.model.TerraformAction;
import lombok.extern.log4j.Log4j2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
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
public class HclTerraformFileParser implements TerraformFileParser {

    private static final Object HCL4J_STDIO_LOCK = new Object();

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
            log.warn("[HCL4J_PARSE_FAILED] Skip Terraform file because hcl4j could not parse it: {}, reason={}",
                fileName, rootMessage(exception));
            return emptyResult();
        }
    }

    private ParseResult parseWithHcl4j(byte[] content, String fileName) throws HCLParserException, IOException {
        Map<String, Object> root = parseRootWithCapturedOutput(content, fileName);
        String rawContent = new String(content, StandardCharsets.UTF_8);

        Set<String> providerBlockNames = new LinkedHashSet<>();
        Map<String, String> localValues = new LinkedHashMap<>();
        List<TerraformAction> actions = new ArrayList<>();

        collectProviderBlocks(value(root, "provider"), providerBlockNames);
        // hcl4j 在不同 Terraform 片段上，locals 根节点既可能表现为 `locals`，
        // 也可能表现为 `local`。这里同时兼容，避免 locals-only 文件在 zip 聚合时丢值。
        collectLocals(value(root, "locals"), localValues);
        collectLocals(value(root, "local"), localValues);
        collectActions(value(root, "resource"), TerraformAction.ProviderType.RESOURCE, fileName, actions);
        collectActions(value(root, "data"), TerraformAction.ProviderType.DATA_SOURCE, fileName, actions);
        supplementMissingTopLevelExpressions(rawContent, actions);

        return new ParseResult()
            .setProviderBlockNames(providerBlockNames)
            .setLocalValues(localValues)
            .setActions(actions);
    }

    private Map<String, Object> parseRootWithCapturedOutput(byte[] content, String fileName)
        throws HCLParserException, IOException {
        synchronized (HCL4J_STDIO_LOCK) {
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
            ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();

            try (
                PrintStream redirectedOut = new PrintStream(capturedOut, true, StandardCharsets.UTF_8);
                PrintStream redirectedErr = new PrintStream(capturedErr, true, StandardCharsets.UTF_8)
            ) {
                System.setOut(redirectedOut);
                System.setErr(redirectedErr);
                return asObject(new HCLParser().parse(new java.io.ByteArrayInputStream(content)));
            } finally {
                System.setOut(originalOut);
                System.setErr(originalErr);
                logCapturedLibraryOutput(fileName, capturedOut, capturedErr);
            }
        }
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
     * `count`、`flavor_id`、`system_disk_size`、`size`。
     */
    private void supplementMissingTopLevelExpressions(String rawContent, List<TerraformAction> actions) {
        if (rawContent == null || rawContent.isBlank() || actions == null || actions.isEmpty()) {
            return;
        }
        for (TerraformAction action : actions) {
            if (action == null
                || action.getProviderType() != TerraformAction.ProviderType.RESOURCE
                || action.getProviderName() == null
                || action.getBlockName() == null) {
                continue;
            }

            String blockBody = findResourceBlockBody(rawContent, action.getProviderName(), action.getBlockName());
            if (blockBody == null) {
                continue;
            }

            supplementExpressionIfMissing(action::getRequestedAmountExpression, action::setRequestedAmountExpression, blockBody, "count");
            supplementExpressionIfMissing(action::getFlavorIdExpression, action::setFlavorIdExpression, blockBody, "flavor_id");
            supplementExpressionIfMissing(action::getSystemDiskSizeExpression, action::setSystemDiskSizeExpression, blockBody, "system_disk_size");
            supplementExpressionIfMissing(action::getVolumeSizeExpression, action::setVolumeSizeExpression, blockBody, "size");
        }
    }

    private void supplementExpressionIfMissing(
        java.util.function.Supplier<String> getter,
        java.util.function.Consumer<String> setter,
        String blockBody,
        String attributeName
    ) {
        if (getter.get() != null) {
            return;
        }
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

    private void logCapturedLibraryOutput(
        String fileName,
        ByteArrayOutputStream capturedOut,
        ByteArrayOutputStream capturedErr
    ) {
        String stdout = normalizeCapturedText(capturedOut);
        if (!stdout.isBlank()) {
            log.debug("[HCL4J_STDOUT_CAPTURED] Suppressed hcl4j stdout for {}: {}", fileName, stdout);
        }

        String stderr = normalizeCapturedText(capturedErr);
        if (!stderr.isBlank()) {
            log.debug("[HCL4J_STDERR_CAPTURED] Suppressed hcl4j stderr for {}: {}", fileName, stderr);
        }
    }

    private void collectProviderBlocks(Object providerNode, Set<String> providerBlockNames) {
        providerBlockNames.addAll(asObject(providerNode).keySet());
    }

    private void collectLocals(Object localsNode, Map<String, String> localValues) {
        if (localsNode instanceof List<?> localsList) {
            localsList.forEach(item -> collectLocals(item, localValues));
            return;
        }

        for (Map.Entry<String, Object> entry : asObject(localsNode).entrySet()) {
            String literal = asSimpleValue(entry.getValue());
            if (literal != null) {
                localValues.put(entry.getKey(), literal);
            }
        }
    }

    private void collectActions(
        Object sectionNode,
        TerraformAction.ProviderType providerType,
        String fileName,
        List<TerraformAction> actions
    ) {
        for (Map.Entry<String, Object> actionEntry : asObject(sectionNode).entrySet()) {
            String terraformType = normalize(actionEntry.getKey());
            if (terraformType == null) {
                log.warn("[PROVIDER_NAME_UNRESOLVED] Could not resolve provider name from action {}: {}",
                    actionEntry.getKey(), fileName);
                continue;
            }
            collectActionInstances(terraformType, actionEntry.getValue(), providerType, actions);
        }
    }

    private void collectActionInstances(
        String terraformType,
        Object actionNode,
        TerraformAction.ProviderType providerType,
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
                .setRequestedAmount(providerType == TerraformAction.ProviderType.DATA_SOURCE ? 1 : 0)
                .setRequestedAmountExpression(providerType == TerraformAction.ProviderType.RESOURCE
                    ? normalizeExpression(value(attributes, "count"))
                    : null)
                .setFlavorIdExpression(providerType == TerraformAction.ProviderType.RESOURCE
                    ? normalizeExpression(value(attributes, "flavor_id"))
                    : null)
                .setSystemDiskSizeExpression(providerType == TerraformAction.ProviderType.RESOURCE
                    ? normalizeExpression(value(attributes, "system_disk_size"))
                    : null)
                .setVolumeSizeExpression(providerType == TerraformAction.ProviderType.RESOURCE
                    ? normalizeExpression(value(attributes, "size"))
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

    private Object value(Map<String, Object> map, String key) {
        return map.get(key);
    }

    private String asSimpleValue(Object value) {
        if (value instanceof Number number) {
            if (number.doubleValue() == Math.floor(number.doubleValue())) {
                return String.valueOf(number.intValue());
            }
            return stripTrailingZero(String.valueOf(number.doubleValue()));
        }
        if (value instanceof String string) {
            return normalizeExpression(string);
        }
        return null;
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

    private String normalizeCapturedText(ByteArrayOutputStream buffer) {
        String text = buffer.toString(StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            return "";
        }
        return text.replace(System.lineSeparator(), " | ");
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
