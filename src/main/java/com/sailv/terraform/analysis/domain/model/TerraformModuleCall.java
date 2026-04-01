package com.sailv.terraform.analysis.domain.model;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Terraform 模块调用定义。
 *
 * <p>这里只保留最小运行时信息：模块名、source 以及调用时传入的顶层参数。
 */
@Getter
@Setter
@Accessors(chain = true)
public class TerraformModuleCall {
    private String moduleName;
    private String source;
    private Map<String, Object> inputValues = new LinkedHashMap<>();

    public TerraformModuleCall() {
    }
}
