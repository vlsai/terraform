package com.sailv.terraform.analysis.domain.model;

/**
 * 命中配额规则的资源结果。
 *
 * <p>这里只输出需要配额检查的资源，不输出模板中所有资源。
 */
public record TemplateQuotaResource(String templateId, String resourceType, String quotaType) {
}
