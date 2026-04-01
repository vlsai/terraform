package com.sailv.terraform.analysis.application.parser;

import com.sailv.terraform.analysis.domain.model.TerraformAction;
import com.sailv.terraform.analysis.domain.model.TerraformModuleCall;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Terraform 文件解析 SPI。
 *
 * <p>parser 只负责把单个文件里的 locals 和动作提取出来，
 * 不在这里做跨文件数量求值。
 *
 * <p>`localValues` / `variableDefaults` 保留的是“原始可解析值”：
 * <ul>
 *     <li>字面量数字，例如 `3`</li>
 *     <li>字面量字符串，例如 `s6.2xlarge.4`</li>
 *     <li>布尔值、列表、对象等简单结构值</li>
 *     <li>`local.xxx` / `var.xxx` 这种简单引用表达式</li>
 * </ul>
 *
 * <p>真正的 `local.xxx` 展开仍然在 service 层统一做，这样 zip 同目录多文件才能共享 locals。
 */
public interface TerraformFileParser {

    boolean supports(String fileName);

    ParseResult parse(InputStream inputStream, String fileName) throws IOException;

    @Getter
    @Setter
    @Accessors(chain = true)
    class ParseResult {
        private Set<String> providerBlockNames = new LinkedHashSet<>();
        private Map<String, Object> localValues = new LinkedHashMap<>();
        private Map<String, Object> variableDefaults = new LinkedHashMap<>();
        private List<TerraformModuleCall> moduleCalls = new ArrayList<>();
        private List<TerraformAction> actions = new ArrayList<>();

        public ParseResult() {
        }
    }
}
