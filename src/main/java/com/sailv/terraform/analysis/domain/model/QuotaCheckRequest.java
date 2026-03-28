package com.sailv.terraform.analysis.domain.model;

public record QuotaCheckRequest(
    Long templateId,
    String providerName,
    String actionName,
    String resourceType,
    String quotaType,
    String checkUrl
) {
}
