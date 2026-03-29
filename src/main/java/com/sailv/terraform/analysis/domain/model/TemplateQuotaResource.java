package com.sailv.terraform.analysis.domain.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 模板资源配额聚合结果。
 *
 * <p>该对象直接对应 `t_mp_template_resource` 一行：
 * `resource_type + quota_type` 表示一种配额维度，
 * `quota_requirement` 表示该模板在该维度上的总需求量。
 */
@Getter
@ToString
@EqualsAndHashCode
@Accessors(fluent = true)
public final class TemplateQuotaResource {
    private final String templateId;
    private final String resourceType;
    private final String quotaType;
    private final int quotaRequirement;

    public TemplateQuotaResource(String templateId, String resourceType, String quotaType, int quotaRequirement) {
        this.templateId = templateId;
        this.resourceType = resourceType;
        this.quotaType = quotaType;
        this.quotaRequirement = quotaRequirement;
    }
}
