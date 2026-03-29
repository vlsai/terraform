package com.sailv.terraform.analysis.infrastructure.parser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sailv.terraform.analysis.domain.model.TerraformAction;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * `.tf.json` 解析器。
 *
 * <p>生产场景下的 Terraform JSON 往往来自不同工具链：
 * 标准 Terraform 输出、平台导出的 JSON、带注释或尾逗号的 JSON 文件都会出现。
 * 因此这里不再使用手写最小 JSON 解析器，而是直接基于 Jackson Tree Model 做容错解析。
 *
 * <p>当前只抽取当前业务真正关心的四类 Terraform 信息：
 * <ul>
 *     <li>provider block 名称</li>
 *     <li>resource block 对应的 Terraform 类型</li>
 *     <li>data block 对应的 Terraform 类型</li>
 *     <li>module block 中的 source</li>
 * </ul>
 *
 * <p>其余字段即使存在，也不会影响主流程；解析不到时仅记录日志并跳过，
 * 尽量保证模板上传场景下“能解析多少就返回多少”。
 */
public class JsonTerraformFileParser implements TerraformFileParser {

    private static final Logger LOGGER = Logger.getLogger(JsonTerraformFileParser.class.getName());

    /**
     * 允许少量现实场景中的“非严格 JSON”写法，减少因为工具链差异导致的误失败。
     */
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
        .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
        .enable(JsonReadFeature.ALLOW_YAML_COMMENTS)
        .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
        .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
        .build();

    @Override
    public boolean supports(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".tf.json");
    }

    @Override
    public ParseResult parse(Path file) throws IOException {
        JsonNode root = readRoot(file);
        if (root == null || root.isMissingNode() || root.isNull()) {
            return emptyResult();
        }
        if (!root.isObject()) {
            LOGGER.warning(() -> "[INVALID_TERRAFORM_JSON] Terraform JSON root must be an object: " + file);
            return emptyResult();
        }

        // 使用 LinkedHashSet / LinkedHashMap 的原因与 HCL 解析器一致：
        // 1. 去重
        // 2. 保持输入出现顺序，方便 debug
        // 3. 让调试打印和测试结果更稳定
        Set<String> providerBlockNames = new LinkedHashSet<>();
        List<TerraformAction> actions = new ArrayList<>();
        List<ModuleReference> moduleReferences = new ArrayList<>();
        Map<String, Integer> localValues = new LinkedHashMap<>();

        collectProviderBlocks(file, root.path("provider"), providerBlockNames);
        collectLocals(file, root.path("locals"), localValues);
        collectActions(root.path("resource"), TerraformAction.Kind.RESOURCE, file, localValues, actions);
        collectActions(root.path("data"), TerraformAction.Kind.DATA_SOURCE, file, localValues, actions);
        collectModules(file, root.path("module"), moduleReferences);

        if (providerBlockNames.isEmpty() && actions.isEmpty() && moduleReferences.isEmpty()) {
            LOGGER.info(() -> "[EMPTY_TERRAFORM_JSON] Terraform JSON file did not contain provider, resource, data, or module blocks: "
                + file);
        }

        return new ParseResult(providerBlockNames, actions, moduleReferences);
    }

    private JsonNode readRoot(Path file) throws IOException {
        try (InputStream inputStream = Files.newInputStream(file)) {
            return OBJECT_MAPPER.readTree(inputStream);
        } catch (JsonProcessingException exception) {
            LOGGER.log(Level.WARNING, "[INVALID_TERRAFORM_JSON] Failed to parse Terraform JSON file " + file, exception);
            return null;
        }
    }

    private ParseResult emptyResult() {
        return new ParseResult(Set.of(), List.of(), List.of());
    }

    /**
     * Terraform JSON 中 provider 段的标准形态是：
     * {"provider":{"huaweicloud":{...},"aws":[{...}]}}
     *
     * <p>因此这里支持 object / array 两种容器，字段名本身就是 providerName。
     */
    private void collectProviderBlocks(Path file, JsonNode providerNode, Set<String> providerBlockNames) {
        if (isEmpty(providerNode)) {
            return;
        }
        if (providerNode.isArray()) {
            for (JsonNode item : providerNode) {
                collectProviderBlocks(file, item, providerBlockNames);
            }
            return;
        }
        if (!providerNode.isObject()) {
            logUnexpectedShape("provider", file, providerNode);
            return;
        }
        providerNode.fieldNames().forEachRemaining(providerName -> addNonBlank(providerBlockNames, providerName));
    }

    private void collectLocals(Path file, JsonNode localsNode, Map<String, Integer> localValues) {
        if (isEmpty(localsNode)) {
            return;
        }
        if (localsNode.isArray()) {
            for (JsonNode item : localsNode) {
                collectLocals(file, item, localValues);
            }
            return;
        }
        if (!localsNode.isObject()) {
            logUnexpectedShape("locals", file, localsNode);
            return;
        }

        Iterator<Map.Entry<String, JsonNode>> fields = localsNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            // 这里只接收可以稳定落成整数的 locals，
            // 用来支持 count = local.xxx。
            Integer value = asInteger(entry.getValue());
            if (value != null) {
                localValues.put(entry.getKey(), value);
            }
        }
    }

    /**
     * Terraform JSON 的 resource / data 段第一层字段名就是 Terraform 类型，
     * 例如：
     * {"resource":{"huaweicloud_compute_instance":{"web":{...}}}}
     *
     * <p>这里会继续深入一层资源实例名，例如 web/db，并尝试提取 count。
     */
    private void collectActions(
        JsonNode sectionNode,
        TerraformAction.Kind kind,
        Path file,
        Map<String, Integer> localValues,
        List<TerraformAction> actions
    ) {
        if (isEmpty(sectionNode)) {
            return;
        }
        if (sectionNode.isArray()) {
            for (JsonNode item : sectionNode) {
                collectActions(item, kind, file, localValues, actions);
            }
            return;
        }
        if (!sectionNode.isObject()) {
            logUnexpectedShape(kind == TerraformAction.Kind.RESOURCE ? "resource" : "data", file, sectionNode);
            return;
        }

        Iterator<Map.Entry<String, JsonNode>> fields = sectionNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            // 第一层 key 就是要入库到 t_mp_template_providers.provider_name 的 Terraform 类型，
            // 例如 huaweicloud_compute_instance、huaweicloud_cce_node。
            addActionInstances(file, kind, entry.getKey(), entry.getValue(), localValues, actions);
        }
    }

    /**
     * module 段中的 source 可能直接位于对象中，也可能被表示成数组中的多个块。
     * 这里递归抽取所有本地或远程 source，是否继续递归 module 目录由 service 决定。
     */
    private void collectModules(Path file, JsonNode moduleNode, List<ModuleReference> moduleReferences) {
        if (isEmpty(moduleNode)) {
            return;
        }
        if (moduleNode.isArray()) {
            for (JsonNode item : moduleNode) {
                collectModules(file, item, moduleReferences);
            }
            return;
        }
        if (!moduleNode.isObject()) {
            logUnexpectedShape("module", file, moduleNode);
            return;
        }

        Iterator<Map.Entry<String, JsonNode>> fields = moduleNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            extractModuleSource(file, entry.getValue(), moduleReferences);
        }
    }

    private void extractModuleSource(Path file, JsonNode moduleConfig, List<ModuleReference> moduleReferences) {
        if (isEmpty(moduleConfig)) {
            return;
        }
        if (moduleConfig.isArray()) {
            for (JsonNode item : moduleConfig) {
                extractModuleSource(file, item, moduleReferences);
            }
            return;
        }
        if (!moduleConfig.isObject()) {
            logUnexpectedShape("module.source", file, moduleConfig);
            return;
        }

        JsonNode sourceNode = moduleConfig.path("source");
        if (sourceNode.isTextual()) {
            String source = sourceNode.asText().trim();
            if (!source.isEmpty()) {
                moduleReferences.add(new ModuleReference(source));
            }
        }
    }

    private void addActionInstances(
        Path file,
        TerraformAction.Kind kind,
        String rawActionName,
        JsonNode actionNode,
        Map<String, Integer> localValues,
        List<TerraformAction> actions
    ) {
        String terraformType = normalize(rawActionName);
        if (terraformType == null) {
            return;
        }

        if (isEmpty(actionNode)) {
            // 尽量保留最小动作信息，避免因为结构不完整导致整块丢失。
            actions.add(new TerraformAction(terraformType, terraformType, terraformType, kind, 1));
            return;
        }
        if (actionNode.isArray()) {
            for (JsonNode item : actionNode) {
                addActionInstances(file, kind, terraformType, item, localValues, actions);
            }
            return;
        }
        if (!actionNode.isObject()) {
            actions.add(new TerraformAction(terraformType, terraformType, terraformType, kind, 1));
            return;
        }

        Iterator<Map.Entry<String, JsonNode>> instances = actionNode.fields();
        boolean foundInstance = false;
        while (instances.hasNext()) {
            Map.Entry<String, JsonNode> instance = instances.next();
            foundInstance = true;
            // 只有 resource 参与配额数量统计。
            int requestedAmount = kind == TerraformAction.Kind.RESOURCE
                ? resolveRequestedAmount(file, instance.getValue(), localValues)
                : 1;
            if (requestedAmount <= 0) {
                // count <= 0 代表不会真正创建资源，不进入入库结果。
                continue;
            }
            actions.add(new TerraformAction(
                terraformType,
                terraformType,
                normalize(instance.getKey()),
                kind,
                requestedAmount
            ));
        }

        if (!foundInstance) {
            actions.add(new TerraformAction(terraformType, terraformType, terraformType, kind, 1));
        }
    }

    private int resolveRequestedAmount(Path file, JsonNode instanceNode, Map<String, Integer> localValues) {
        if (instanceNode == null || !instanceNode.isObject()) {
            return 1;
        }
        JsonNode countNode = instanceNode.path("count");
        if (isEmpty(countNode)) {
            return 1;
        }

        Integer literalCount = asInteger(countNode);
        if (literalCount != null) {
            return Math.max(literalCount, 0);
        }

        // 这里只支持两种业务明确要求的写法：
        // 1. count = 2
        // 2. count = local.xxx / ${local.xxx}
        //
        // 其余表达式不尝试求值，以免在生产环境引入半吊子的 Terraform 解释器。
        String expression = normalizeExpression(countNode.asText(null));
        if (expression == null) {
            return 1;
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

        LOGGER.info(() -> "[UNSUPPORTED_COUNT_EXPRESSION] Unsupported Terraform JSON count expression "
            + expression + ": " + file);
        return 1;
    }

    private void addNonBlank(Set<String> target, String rawValue) {
        String normalized = normalize(rawValue);
        if (normalized != null) {
            target.add(normalized);
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Integer asInteger(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.asInt();
        }
        if (node.isFloatingPointNumber()) {
            double value = node.asDouble();
            if (Math.floor(value) == value) {
                return (int) value;
            }
            return null;
        }
        if (node.isTextual()) {
            String normalized = normalizeExpression(node.asText());
            if (normalized == null) {
                return null;
            }
            if (normalized.chars().allMatch(character -> Character.isDigit(character) || character == '-')) {
                try {
                    return Integer.parseInt(normalized);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
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

    private void logUnexpectedShape(String sectionName, Path file, JsonNode node) {
        LOGGER.warning(() -> "[UNEXPECTED_TERRAFORM_JSON_SECTION] Unexpected JSON node for " + sectionName
            + " in " + file + ", nodeType=" + node.getNodeType());
    }
}
