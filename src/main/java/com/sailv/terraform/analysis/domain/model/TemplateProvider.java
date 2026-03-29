package com.sailv.terraform.analysis.domain.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 模板实际使用到的 provider，面向 {@code t_mp_template_providers} 落库。
 */
@Getter
@ToString
@EqualsAndHashCode
@Accessors(fluent = true)
public final class TemplateProvider {
    private final String templateId;
    private final String providerName;
    private final String providerType;

    public TemplateProvider(String templateId, String providerName, String providerType) {
        this.templateId = templateId;
        this.providerName = providerName;
        this.providerType = providerType;
    }
}
