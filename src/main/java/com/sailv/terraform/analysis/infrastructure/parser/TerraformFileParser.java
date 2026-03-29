package com.sailv.terraform.analysis.infrastructure.parser;

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
 * Terraform 文件解析器 SPI。
 *
 * <p>解析器只负责把文件内容翻译成领域能够理解的 provider/action/module 信息，
 * 不在这里写业务映射逻辑。
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

        public ParseResult(Set<String> providerBlockNames, List<TerraformAction> actions,
                           List<ModuleReference> moduleReferences) {
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
