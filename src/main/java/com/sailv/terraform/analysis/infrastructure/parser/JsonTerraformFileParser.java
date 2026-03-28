package com.sailv.terraform.analysis.infrastructure.parser;

import com.sailv.terraform.analysis.application.model.DiscoveredModuleReference;
import com.sailv.terraform.analysis.application.model.DiscoveredTerraformAction;
import com.sailv.terraform.analysis.application.model.ParsedTerraformFile;
import com.sailv.terraform.analysis.domain.model.AnalysisWarning;
import com.sailv.terraform.analysis.domain.model.TerraformActionKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class JsonTerraformFileParser implements TerraformFileParser {

    @Override
    public boolean supports(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".tf.json");
    }

    @Override
    public ParsedTerraformFile parse(Path file) throws IOException {
        Object rootValue = new JsonParser(Files.readString(file)).parse();
        Map<String, Object> root = asObject(rootValue);

        Set<String> providerBlockNames = new LinkedHashSet<>();
        Set<String> requiredProviderNames = new LinkedHashSet<>();
        Set<DiscoveredTerraformAction> actions = new LinkedHashSet<>();
        List<DiscoveredModuleReference> moduleReferences = new ArrayList<>();
        List<AnalysisWarning> warnings = new ArrayList<>();

        collectRequiredProviders(value(root, "terraform"), requiredProviderNames);
        collectProviderBlocks(value(root, "provider"), providerBlockNames);
        collectActions(value(root, "resource"), TerraformActionKind.RESOURCE, file, actions);
        collectActions(value(root, "data"), TerraformActionKind.DATA_SOURCE, file, actions);
        collectModules(file, value(root, "module"), moduleReferences);

        if (providerBlockNames.isEmpty() && requiredProviderNames.isEmpty() && actions.isEmpty()) {
            warnings.add(new AnalysisWarning(
                "EMPTY_TERRAFORM_JSON",
                "Terraform JSON file did not contain provider, resource, data, or module blocks",
                file.toString()
            ));
        }

        return new ParsedTerraformFile(providerBlockNames, requiredProviderNames, actions, moduleReferences, warnings);
    }

    private void collectRequiredProviders(Object terraformNode, Set<String> requiredProviderNames) {
        if (terraformNode instanceof List<?> terraformNodes) {
            terraformNodes.forEach(node -> collectRequiredProviders(node, requiredProviderNames));
            return;
        }
        Map<String, Object> terraform = asObject(terraformNode);
        Map<String, Object> requiredProviders = asObject(value(terraform, "required_providers"));
        requiredProviderNames.addAll(requiredProviders.keySet());
    }

    private void collectProviderBlocks(Object providerNode, Set<String> providerBlockNames) {
        providerBlockNames.addAll(asObject(providerNode).keySet());
    }

    private void collectActions(
        Object sectionNode,
        TerraformActionKind kind,
        Path file,
        Set<DiscoveredTerraformAction> actions
    ) {
        for (String actionName : asObject(sectionNode).keySet()) {
            String providerName = providerNameFromAction(actionName);
            if (providerName != null) {
                actions.add(new DiscoveredTerraformAction(providerName, actionName, kind, file.toString()));
            }
        }
    }

    private void collectModules(Path file, Object moduleNode, List<DiscoveredModuleReference> moduleReferences) {
        for (Object moduleConfig : asObject(moduleNode).values()) {
            extractModuleSource(file, moduleConfig, moduleReferences);
        }
    }

    private void extractModuleSource(Path file, Object moduleConfig, List<DiscoveredModuleReference> moduleReferences) {
        if (moduleConfig instanceof List<?> modules) {
            modules.forEach(node -> extractModuleSource(file, node, moduleReferences));
            return;
        }
        String source = asString(value(asObject(moduleConfig), "source"));
        if (source != null && !source.isBlank()) {
            moduleReferences.add(new DiscoveredModuleReference(source, file.toString()));
        }
    }

    private String providerNameFromAction(String actionName) {
        if (actionName == null || actionName.isBlank()) {
            return null;
        }
        int separator = actionName.indexOf('_');
        if (separator <= 0) {
            return null;
        }
        return actionName.substring(0, separator);
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

    private static final class JsonParser {
        private final String text;
        private int index;

        private JsonParser(String text) {
            this.text = text;
        }

        private Object parse() {
            skipWhitespace();
            Object value = parseValue();
            skipWhitespace();
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (isEnd()) {
                return null;
            }
            return switch (current()) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> values = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                index++;
                return values;
            }
            while (!isEnd()) {
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                values.put(key, value);
                skipWhitespace();
                if (peek('}')) {
                    index++;
                    return values;
                }
                expect(',');
            }
            return values;
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> values = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                index++;
                return values;
            }
            while (!isEnd()) {
                values.add(parseValue());
                skipWhitespace();
                if (peek(']')) {
                    index++;
                    return values;
                }
                expect(',');
            }
            return values;
        }

        private Object parseLiteral(String literal, Object value) {
            if (!text.startsWith(literal, index)) {
                throw new IllegalArgumentException("Invalid JSON literal near index " + index);
            }
            index += literal.length();
            return value;
        }

        private Number parseNumber() {
            int start = index;
            while (!isEnd()) {
                char current = current();
                if (!(Character.isDigit(current) || current == '-' || current == '+' || current == '.'
                    || current == 'e' || current == 'E')) {
                    break;
                }
                index++;
            }
            String raw = text.substring(start, index);
            if (raw.contains(".") || raw.contains("e") || raw.contains("E")) {
                return Double.parseDouble(raw);
            }
            return Long.parseLong(raw);
        }

        private String parseString() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (!isEnd()) {
                char current = current();
                if (current == '\\') {
                    index++;
                    if (isEnd()) {
                        break;
                    }
                    builder.append(readEscape());
                    continue;
                }
                if (current == '"') {
                    index++;
                    return builder.toString();
                }
                builder.append(current);
                index++;
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        private char readEscape() {
            char escaped = current();
            index++;
            return switch (escaped) {
                case '"', '\\', '/' -> escaped;
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'u' -> {
                    String unicode = text.substring(index, index + 4);
                    index += 4;
                    yield (char) Integer.parseInt(unicode, 16);
                }
                default -> escaped;
            };
        }

        private void skipWhitespace() {
            while (!isEnd() && Character.isWhitespace(current())) {
                index++;
            }
        }

        private void expect(char expected) {
            skipWhitespace();
            if (isEnd() || current() != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' near index " + index);
            }
            index++;
        }

        private boolean peek(char expected) {
            return !isEnd() && current() == expected;
        }

        private char current() {
            return text.charAt(index);
        }

        private boolean isEnd() {
            return index >= text.length();
        }
    }
}
