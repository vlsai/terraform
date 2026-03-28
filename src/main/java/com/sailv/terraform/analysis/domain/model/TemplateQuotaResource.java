package com.sailv.terraform.analysis.domain.model;

public record TemplateQuotaResource(Long templateId, String resourceType, String quotaType) {
}
