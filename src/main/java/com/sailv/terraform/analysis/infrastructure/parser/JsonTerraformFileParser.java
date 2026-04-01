package com.sailv.terraform.analysis.infrastructure.parser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sailv.terraform.analysis.application.parser.TerraformFileParser;
import com.sailv.terraform.analysis.domain.model.ProviderType;
import com.sailv.terraform.analysis.domain.model.TerraformAction;
import com.sailv.terraform.analysis.domain.model.TerraformModuleCall;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * `.tf.json` 解析器。
 *
 * <p>与 `.tf` 解析器保持同样的职责边界：
 * 这里只负责提取 provider / locals / actions，以及原始
 * `count` / `flavor_id` / `system_disk_size` / `size` 表达式，
 * 不在 parser 阶段做跨文件求值。
 *
 * <p>zip 内多个 `.tf.json` 文件会先按目录聚合，再统一解析 `local.xxx`。
 * 所以这里同样只保留原始表达式，不直接把数量或规格算成最终值。
 */
@Log4j2
@Component
public class JsonTerraformFileParser implements TerraformFileParser {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
        .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
        .enable(JsonReadFeature.ALLOW_YAML_COMMENTS)
        .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
        .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
        .build();

    @Override
    public boolean supports(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".tf.json");
    }

    @Override
    public ParseResult parse(InputStream inputStream, String fileName) throws IOException {
        JsonNode root = readRoot(inputStream, fileName);
        if (root == null || root.isMissingNode() || root.isNull()) {
            return emptyResult();
        }
        if (!root.isObject()) {
            log.error("Terraform JSON root must be an object. file={}", fileName);
            return emptyResult();
        }

        Set<String> providerBlockNames = new LinkedHashSet<>();
        Map<String, Object> localValues = new LinkedHashMap<>();
        Map<String, Object> variableDefaults = new LinkedHashMap<>();
        List<TerraformModuleCall> moduleCalls = new ArrayList<>();
        List<TerraformAction> actions = new ArrayList<>();

        collectProviderBlocks(fileName, root.path("provider"), providerBlockNames);
        collectLocals(fileName, root.path("locals"), localValues);
        collectLocals(fileName, root.path("local"), localValues);
        collectVariables(fileName, root.path("variable"), variableDefaults);
        collectModuleCalls(fileName, root.path("module"), moduleCalls);
        collectActions(root.path("resource"), ProviderType.RESOURCE, fileName, actions);
        collectActions(root.path("data"), ProviderType.DATA, fileName, actions);

        return new ParseResult()
            .setProviderBlockNames(providerBlockNames)
            .setLocalValues(localValues)
            .setVariableDefaults(variableDefaults)
            .setModuleCalls(moduleCalls)
            .setActions(actions);
    }

    private JsonNode readRoot(InputStream inputStream, String fileName) throws IOException {
        try {
            return OBJECT_MAPPER.readTree(inputStream);
        } catch (JsonProcessingException exception) {
            log.error("Failed to parse Terraform JSON file. file={}", fileName, exception);
            return null;
        }
    }

    private ParseResult emptyResult() {
        return new ParseResult();
    }

    private void collectProviderBlocks(String fileName, JsonNode providerNode, Set<String> providerBlockNames) {
        if (isEmpty(providerNode)) {
            return;
        }
        if (providerNode.isArray()) {
            for (JsonNode item : providerNode) {
                collectProviderBlocks(fileName, item, providerBlockNames);
            }
            return;
        }
        if (!providerNode.isObject()) {
            logUnexpectedShape("provider", fileName, providerNode);
            return;
        }
        providerNode.fieldNames().forEachRemaining(providerName -> addNonBlank(providerBlockNames, providerName));
    }

    private void collectLocals(String fileName, JsonNode localsNode, Map<String, Object> localValues) {
        if (isEmpty(localsNode)) {
            return;
        }
        if (localsNode.isArray()) {
            for (JsonNode item : localsNode) {
                collectLocals(fileName, item, localValues);
            }
            return;
        }
        if (!localsNode.isObject()) {
            logUnexpectedShape("locals", fileName, localsNode);
            return;
        }

        Iterator<Map.Entry<String, JsonNode>> fields = localsNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            Object value = asStructuredValue(entry.getValue());
            if (value != null) {
                localValues.put(entry.getKey(), value);
            }
        }
    }

    private void collectVariables(String fileName, JsonNode variableNode, Map<String, Object> variableDefaults) {
        if (isEmpty(variableNode)) {
            return;
        }
        if (variableNode.isArray()) {
            for (JsonNode item : variableNode) {
                collectVariables(fileName, item, variableDefaults);
            }
            return;
        }
        if (!variableNode.isObject()) {
            logUnexpectedShape("variable", fileName, variableNode);
            return;
        }

        Iterator<Map.Entry<String, JsonNode>> fields = variableNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode defaultNode = entry.getValue().path("default");
            if (!isEmpty(defaultNode)) {
                variableDefaults.put(entry.getKey(), asStructuredValue(defaultNode));
            }
        }
    }

    private void collectModuleCalls(String fileName, JsonNode moduleNode, List<TerraformModuleCall> moduleCalls) {
        if (isEmpty(moduleNode)) {
            return;
        }
        if (moduleNode.isArray()) {
            for (JsonNode item : moduleNode) {
                collectModuleCalls(fileName, item, moduleCalls);
            }
            return;
        }
        if (!moduleNode.isObject()) {
            logUnexpectedShape("module", fileName, moduleNode);
            return;
        }

        Iterator<Map.Entry<String, JsonNode>> fields = moduleNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String moduleName = normalize(entry.getKey());
            if (moduleName == null || !entry.getValue().isObject()) {
                continue;
            }
            Map<String, Object> inputValues = new LinkedHashMap<>();
            entry.getValue().fields().forEachRemaining(attribute -> {
                if (!"source".equals(attribute.getKey())) {
                    inputValues.put(attribute.getKey(), asStructuredValue(attribute.getValue()));
                }
            });
            moduleCalls.add(new TerraformModuleCall()
                .setModuleName(moduleName)
                .setSource(normalizeExpression(readScalarOrJson(entry.getValue().get("source"))))
                .setInputValues(inputValues));
        }
    }

    private void collectActions(
        JsonNode sectionNode,
        ProviderType providerType,
        String fileName,
        List<TerraformAction> actions
    ) {
        if (isEmpty(sectionNode)) {
            return;
        }
        if (sectionNode.isArray()) {
            for (JsonNode item : sectionNode) {
                collectActions(item, providerType, fileName, actions);
            }
            return;
        }
        if (!sectionNode.isObject()) {
            logUnexpectedShape(providerType == ProviderType.RESOURCE ? "resource" : "data", fileName, sectionNode);
            return;
        }

        Iterator<Map.Entry<String, JsonNode>> fields = sectionNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            addActionInstances(entry.getKey(), entry.getValue(), providerType, actions);
        }
    }

    private void addActionInstances(
        String rawActionName,
        JsonNode actionNode,
        ProviderType providerType,
        List<TerraformAction> actions
    ) {
        String terraformType = normalize(rawActionName);
        if (terraformType == null) {
            return;
        }

        if (isEmpty(actionNode) || !actionNode.isObject()) {
            actions.add(new TerraformAction()
                .setProviderName(terraformType)
                .setBlockName(terraformType)
                .setProviderType(providerType)
                .setRequestedAmount(providerType == ProviderType.DATA ? 1 : 0));
            return;
        }

        Iterator<Map.Entry<String, JsonNode>> instances = actionNode.fields();
        boolean foundInstance = false;
        while (instances.hasNext()) {
            Map.Entry<String, JsonNode> instance = instances.next();
            foundInstance = true;
            String blockName = normalize(instance.getKey());
            if (blockName == null) {
                blockName = terraformType;
            }
            TerraformAction action = new TerraformAction()
                .setProviderName(terraformType)
                .setBlockName(blockName)
                .setProviderType(providerType)
                .setRequestedAmount(providerType == ProviderType.DATA ? 1 : 0)
                .setRequestedAmountExpression(providerType == ProviderType.RESOURCE
                    ? normalizeExpression(readExpression(instance.getValue(), "count"))
                    : null)
                .setFlavorIdExpression(providerType == ProviderType.RESOURCE
                    ? normalizeExpression(readExpression(instance.getValue(), "flavor_id"))
                    : null)
                .setSystemDiskSizeExpression(providerType == ProviderType.RESOURCE
                    ? normalizeExpression(readExpression(instance.getValue(), "system_disk_size"))
                    : null)
                .setVolumeSizeExpression(providerType == ProviderType.RESOURCE
                    ? normalizeExpression(readExpression(instance.getValue(), "size"))
                    : null);
            actions.add(action);
        }

        if (!foundInstance) {
            actions.add(new TerraformAction()
                .setProviderName(terraformType)
                .setBlockName(terraformType)
                .setProviderType(providerType)
                .setRequestedAmount(providerType == ProviderType.DATA ? 1 : 0));
        }
    }

    private String readExpression(JsonNode instanceNode, String fieldName) {
        if (instanceNode == null || !instanceNode.isObject()) {
            return null;
        }
        JsonNode fieldNode = instanceNode.path(fieldName);
        if (isEmpty(fieldNode)) {
            return null;
        }
        if (fieldNode.isNumber()) {
            return fieldNode.asText();
        }
        if (fieldNode.isTextual()) {
            return fieldNode.asText();
        }
        return fieldNode.toString();
    }

    private String readScalarOrJson(JsonNode node) {
        if (isEmpty(node)) {
            return null;
        }
        if (node.isTextual() || node.isNumber() || node.isBoolean()) {
            return node.asText();
        }
        return node.toString();
    }

    private void addNonBlank(Set<String> target, String rawValue) {
        String normalized = normalize(rawValue);
        if (normalized != null) {
            target.add(normalized);
        }
    }

    private Object asStructuredValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.intValue();
        }
        if (node.isFloatingPointNumber()) {
            return node.doubleValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isTextual()) {
            return normalizeExpression(node.asText());
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            node.forEach(item -> values.add(asStructuredValue(item)));
            return values;
        }
        if (node.isObject()) {
            Map<String, Object> values = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> values.put(entry.getKey(), asStructuredValue(entry.getValue())));
            return values;
        }
        return null;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeExpression(String expression) {
        String normalized = normalize(expression);
        if (normalized == null) {
            return null;
        }
        if (normalized.startsWith("${") && normalized.endsWith("}")) {
            normalized = normalize(normalized.substring(2, normalized.length() - 1));
        }
        return normalized;
    }

    private boolean isEmpty(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull();
    }

    private void logUnexpectedShape(String sectionName, String fileName, JsonNode node) {
        log.info("Unexpected Terraform JSON section shape. section={}, file={}, nodeType={}",
            sectionName, fileName, node.getNodeType());
    }
}
