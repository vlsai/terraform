package com.sailv.terraform.analysis.application.parser;

import com.sailv.terraform.analysis.domain.model.TerraformAction;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Terraform file parser SPI shared by the application/service flow.
 */
public interface TerraformFileParser {

    boolean supports(Path file);

    ParseResult parse(Path file) throws IOException;

    @Getter
    @ToString
    @EqualsAndHashCode
    @Accessors(fluent = true)
    final class ParseResult {
        private final Set<String> providerBlockNames;
        private final List<TerraformAction> actions;
        private final List<ModuleReference> moduleReferences;

        public ParseResult(
            Set<String> providerBlockNames,
            List<TerraformAction> actions,
            List<ModuleReference> moduleReferences
        ) {
            this.providerBlockNames = providerBlockNames == null ? Set.of() : Set.copyOf(providerBlockNames);
            this.actions = actions == null ? List.of() : List.copyOf(actions);
            this.moduleReferences = moduleReferences == null ? List.of() : List.copyOf(moduleReferences);
        }
    }

    @Getter
    @ToString
    @EqualsAndHashCode
    @Accessors(fluent = true)
    final class ModuleReference {
        private final String rawSource;

        public ModuleReference(String rawSource) {
            this.rawSource = rawSource;
        }
    }
}
