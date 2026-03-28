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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class HclTerraformFileParser implements TerraformFileParser {

    @Override
    public boolean supports(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".tf") && !name.endsWith(".tf.json");
    }

    @Override
    public ParsedTerraformFile parse(Path file) throws IOException {
        String content = Files.readString(file);
        HclEntryParser parser = new HclEntryParser(content);
        List<HclEntry> entries = parser.parseEntries();

        Set<String> providerBlockNames = new LinkedHashSet<>();
        Set<String> requiredProviderNames = new LinkedHashSet<>();
        Set<DiscoveredTerraformAction> actions = new LinkedHashSet<>();
        List<DiscoveredModuleReference> moduleReferences = new ArrayList<>();
        List<AnalysisWarning> warnings = new ArrayList<>();

        for (HclEntry entry : entries) {
            if (entry.entryType() != EntryType.BLOCK) {
                continue;
            }
            switch (entry.name()) {
                case "provider" -> {
                    if (!entry.labels().isEmpty()) {
                        providerBlockNames.add(entry.labels().getFirst());
                    }
                }
                case "resource" -> addAction(entry, TerraformActionKind.RESOURCE, file, actions, warnings);
                case "data" -> addAction(entry, TerraformActionKind.DATA_SOURCE, file, actions, warnings);
                case "module" -> collectModuleReference(entry, file, moduleReferences);
                case "terraform" -> collectRequiredProviders(entry, requiredProviderNames);
                default -> {
                }
            }
        }

        return new ParsedTerraformFile(providerBlockNames, requiredProviderNames, actions, moduleReferences, warnings);
    }

    private void addAction(
        HclEntry entry,
        TerraformActionKind kind,
        Path file,
        Set<DiscoveredTerraformAction> actions,
        List<AnalysisWarning> warnings
    ) {
        if (entry.labels().size() < 2) {
            warnings.add(new AnalysisWarning(
                "INVALID_ACTION_BLOCK",
                "Expected two labels in " + entry.name() + " block",
                file.toString()
            ));
            return;
        }
        String actionName = entry.labels().getFirst();
        String providerName = providerNameFromAction(actionName);
        if (providerName == null) {
            warnings.add(new AnalysisWarning(
                "PROVIDER_NAME_UNRESOLVED",
                "Could not resolve provider name from action " + actionName,
                file.toString()
            ));
            return;
        }
        actions.add(new DiscoveredTerraformAction(providerName, actionName, kind, file.toString()));
    }

    private void collectModuleReference(HclEntry moduleBlock, Path file, List<DiscoveredModuleReference> moduleReferences) {
        List<HclEntry> nestedEntries = new HclEntryParser(moduleBlock.body()).parseEntries();
        nestedEntries.stream()
            .filter(entry -> entry.entryType() == EntryType.ASSIGNMENT)
            .filter(entry -> "source".equals(entry.name()))
            .map(HclEntry::stringValue)
            .filter(value -> value != null && !value.isBlank())
            .findFirst()
            .ifPresent(source -> moduleReferences.add(new DiscoveredModuleReference(source, file.toString())));
    }

    private void collectRequiredProviders(HclEntry terraformBlock, Set<String> requiredProviderNames) {
        List<HclEntry> terraformEntries = new HclEntryParser(terraformBlock.body()).parseEntries();
        terraformEntries.stream()
            .filter(entry -> entry.entryType() == EntryType.BLOCK)
            .filter(entry -> "required_providers".equals(entry.name()))
            .forEach(requiredProvidersBlock -> {
                List<HclEntry> providerEntries = new HclEntryParser(requiredProvidersBlock.body()).parseEntries();
                providerEntries.stream()
                    .filter(entry -> entry.entryType() == EntryType.ASSIGNMENT)
                    .map(HclEntry::name)
                    .forEach(requiredProviderNames::add);
            });
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

    private enum EntryType {
        BLOCK,
        ASSIGNMENT
    }

    private record HclEntry(
        EntryType entryType,
        String name,
        List<String> labels,
        String body,
        String stringValue
    ) {
        static HclEntry block(String name, List<String> labels, String body) {
            return new HclEntry(EntryType.BLOCK, name, List.copyOf(labels), body, null);
        }

        static HclEntry assignment(String name, String stringValue) {
            return new HclEntry(EntryType.ASSIGNMENT, name, List.of(), null, stringValue);
        }
    }

    private static final class HclEntryParser {
        private final String text;
        private int index;

        private HclEntryParser(String text) {
            this.text = text;
        }

        private List<HclEntry> parseEntries() {
            List<HclEntry> entries = new ArrayList<>();
            while (true) {
                skipWhitespaceAndComments();
                if (isEnd()) {
                    return entries;
                }
                HclEntry entry = parseEntry();
                if (entry != null) {
                    entries.add(entry);
                    continue;
                }
                index++;
            }
        }

        private HclEntry parseEntry() {
            String name = parseIdentifier();
            if (name == null) {
                return null;
            }

            skipWhitespaceAndComments();
            List<String> labels = new ArrayList<>();
            while (!isEnd()) {
                skipWhitespaceAndComments();
                if (isEnd()) {
                    return null;
                }
                char current = current();
                if (current == '{') {
                    String body = readBalanced('{', '}');
                    return HclEntry.block(name, labels, body);
                }
                if (current == '=') {
                    index++;
                    skipWhitespaceAndComments();
                    ParsedValue value = parseValue();
                    return HclEntry.assignment(name, value.stringValue());
                }
                String label = parseLabel();
                if (label != null) {
                    labels.add(label);
                    continue;
                }
                return null;
            }
            return null;
        }

        private ParsedValue parseValue() {
            if (isEnd()) {
                return new ParsedValue(null);
            }
            char current = current();
            if (current == '"') {
                return new ParsedValue(parseString());
            }
            if (current == '{') {
                readBalanced('{', '}');
                return new ParsedValue(null);
            }
            if (current == '[') {
                readBalanced('[', ']');
                return new ParsedValue(null);
            }
            int start = index;
            while (!isEnd()) {
                char valueChar = current();
                if (valueChar == '\n' || valueChar == '\r') {
                    break;
                }
                if (valueChar == '#' || startsWith("//") || startsWith("/*")) {
                    break;
                }
                index++;
            }
            String raw = text.substring(start, index).trim();
            return new ParsedValue(raw.isEmpty() ? null : raw);
        }

        private String parseLabel() {
            if (isEnd()) {
                return null;
            }
            if (current() == '"') {
                return parseString();
            }
            return parseIdentifier();
        }

        private String parseIdentifier() {
            if (isEnd() || !isIdentifierStart(current())) {
                return null;
            }
            int start = index;
            index++;
            while (!isEnd() && isIdentifierPart(current())) {
                index++;
            }
            return text.substring(start, index);
        }

        private String parseString() {
            if (current() != '"') {
                return null;
            }
            index++;
            StringBuilder value = new StringBuilder();
            while (!isEnd()) {
                char current = current();
                if (current == '\\') {
                    if (index + 1 < text.length()) {
                        value.append(text.charAt(index + 1));
                        index += 2;
                        continue;
                    }
                    index++;
                    continue;
                }
                if (current == '"') {
                    index++;
                    return value.toString();
                }
                value.append(current);
                index++;
            }
            return value.toString();
        }

        private String readBalanced(char open, char close) {
            if (current() != open) {
                return "";
            }
            int start = index + 1;
            int depth = 1;
            index++;
            while (!isEnd()) {
                char current = current();
                if (current == '"') {
                    parseString();
                    continue;
                }
                if (current == '#' || startsWith("//")) {
                    skipLineComment();
                    continue;
                }
                if (startsWith("/*")) {
                    skipBlockComment();
                    continue;
                }
                if (current == open) {
                    depth++;
                    index++;
                    continue;
                }
                if (current == close) {
                    depth--;
                    if (depth == 0) {
                        String body = text.substring(start, index);
                        index++;
                        return body;
                    }
                    index++;
                    continue;
                }
                index++;
            }
            return text.substring(start);
        }

        private void skipWhitespaceAndComments() {
            while (!isEnd()) {
                char current = current();
                if (Character.isWhitespace(current)) {
                    index++;
                    continue;
                }
                if (current == '#') {
                    skipLineComment();
                    continue;
                }
                if (startsWith("//")) {
                    skipLineComment();
                    continue;
                }
                if (startsWith("/*")) {
                    skipBlockComment();
                    continue;
                }
                return;
            }
        }

        private void skipLineComment() {
            while (!isEnd()) {
                char current = current();
                index++;
                if (current == '\n') {
                    return;
                }
            }
        }

        private void skipBlockComment() {
            index += 2;
            while (!isEnd()) {
                if (startsWith("*/")) {
                    index += 2;
                    return;
                }
                index++;
            }
        }

        private boolean startsWith(String value) {
            return text.startsWith(value, index);
        }

        private boolean isIdentifierStart(char value) {
            return Character.isLetter(value) || value == '_' || value == '-';
        }

        private boolean isIdentifierPart(char value) {
            return Character.isLetterOrDigit(value) || value == '_' || value == '-' || value == '.';
        }

        private char current() {
            return text.charAt(index);
        }

        private boolean isEnd() {
            return index >= text.length();
        }
    }

    private record ParsedValue(String stringValue) {
    }
}
