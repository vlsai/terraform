package com.sailv.terraform.analysis.domain.model;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * `t_mp_template_providers` 对应领域对象。
 */
@Getter
@Setter
@Accessors(chain = true)
public class TemplateProvider {
    private String templateId;
    private String providerName;
    private String providerType;

    public TemplateProvider() {
    }

    public TemplateProvider(String templateId, String providerName, String providerType) {
        this.templateId = templateId;
        this.providerName = providerName;
        this.providerType = providerType;
    }
}
