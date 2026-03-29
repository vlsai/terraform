package com.sailv.terraform.analysis.domain.model;

/**
 * 模板实际使用到的 provider，面向 {@code t_mp_template_providers} 落库。
 */
public record TemplateProvider(String templateId, String providerName, String providerType) {
}
